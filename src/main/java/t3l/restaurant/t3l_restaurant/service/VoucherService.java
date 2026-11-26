package t3l.restaurant.t3l_restaurant.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import t3l.restaurant.t3l_restaurant.dto.VoucherForm;
import t3l.restaurant.t3l_restaurant.entity.*;
import t3l.restaurant.t3l_restaurant.repository.*;

@Service
public class VoucherService {
  private static final List<String> BLOCKED_QR = List.of(PaymentIntent.PENDING, PaymentIntent.RECEIVED,
      PaymentIntent.REVIEW_REQUIRED, PaymentIntent.APPLIED);
  private final VoucherRepository vouchers;
  private final VoucherUsageRepository usages;
  private final PaymentIntentRepository intents;
  private final ReservationSchedule schedule;
  private final byte[] secret;

  public VoucherService(VoucherRepository vouchers, VoucherUsageRepository usages, PaymentIntentRepository intents,
      ReservationSchedule schedule, @Value("${app.jwt.secret}") String secret) {
    this.vouchers=vouchers;
    this.usages=usages;
    this.intents=intents;
    this.schedule=schedule;
    this.secret=secret.getBytes(StandardCharsets.UTF_8);
  }

  public record Row(Voucher voucher,String state,long used,Long remaining,BigDecimal discount) {}

  @Transactional(readOnly=true)
  public List<Row> list(String query, String state) {
    String term=Objects.toString(query,"").strip().toLowerCase(Locale.ROOT);
    return vouchers.findAllByOrderByIdDesc().stream()
        .filter(v -> v.getCode().toLowerCase(Locale.ROOT).contains(term) || v.getTitle().toLowerCase(Locale.ROOT).contains(term))
        .map(v -> row(v))
        .filter(row -> state==null || state.isBlank() || state.equals(row.state())).toList();
  }

  private Row row(Voucher voucher) {
    var totals=usages.totals(voucher.getId());
    Integer limit=voucher.getTotalLimit();
    return new Row(voucher,voucher.state(now()),totals.getUsed(),
        limit==null ? null : Math.max(0L,limit-usages.allocated(voucher.getId())),totals.getDiscount());
  }

  @Transactional(readOnly=true)
  public Voucher get(Long id) {
    return vouchers.findById(id).orElseThrow(() -> invalid("Không tìm thấy voucher."));
  }

  @Transactional(readOnly=true)
  public List<VoucherUsage> history(Long id, int page) {
    return usages.findByVoucherIdOrderByIdDesc(id, PageRequest.of(Math.max(0,page),26));
  }

  @Transactional(readOnly=true)
  public List<Voucher> offers(Long buyer, boolean expired) {
    String customer=buyer==null ? null : "buyer:"+buyer;
    return vouchers.findByPublicOfferTrueOrderByEndsAtAsc().stream().filter(v -> expired
        ? "EXPIRED".equals(v.state(now())) : "ACTIVE".equals(v.state(now())) && hasRoom(v,customer)).toList();
  }

  @Transactional(readOnly=true)
  public List<VoucherUsage> used(Long buyer) {
    return usages.findByCustomerKeyAndStatusOrderByIdDesc("buyer:"+buyer,VoucherUsage.USED,PageRequest.of(0,100));
  }

  @Transactional
  public void save(Long id,VoucherForm form) {
    String code=normalize(form.getCode());
    if (code.isEmpty() || form.getStartsAt()==null || form.getEndsAt()==null || !form.getStartsAt().isBefore(form.getEndsAt())) {
      throw invalid("Nhập mã voucher và khoảng thời gian bắt đầu, kết thúc hợp lệ.");
    }
    BigDecimal value=form.getDiscountValue();
    if (value==null || value.signum()<=0 || !List.of("PERCENT","FIXED").contains(form.getDiscountType())
        || ("PERCENT".equals(form.getDiscountType()) && value.compareTo(BigDecimal.valueOf(100))>0)
        || ("FIXED".equals(form.getDiscountType()) && value.stripTrailingZeros().scale()>0)) {
      throw invalid("Mức giảm phần trăm phải từ trên 0 đến 100%, hoặc nhập số tiền nguyên dương.");
    }
    if (form.getCustomerLimit()!=null && form.getTotalLimit()!=null && form.getCustomerLimit()>form.getTotalLimit()) {
      throw invalid("Giới hạn mỗi khách không được vượt tổng lượt.");
    }
    Voucher voucher=id==null ? new Voucher() : vouchers.lockId(id).orElseThrow(() -> invalid("Không tìm thấy voucher."));
    if (id!=null && form.getTotalLimit()!=null && form.getTotalLimit()<usages.allocated(id)) {
      throw invalid("Tổng giới hạn phải đủ cho các lượt đã giữ hoặc đã sử dụng.");
    }
    if (vouchers.findByCode(code).filter(duplicate -> !Objects.equals(id,duplicate.getId())).isPresent()) {
      throw invalid("Mã voucher đã tồn tại.");
    }
    voucher.update(form,code);
    vouchers.saveAndFlush(voucher);
  }

  @Transactional
  public void setEnabled(Long id,boolean enabled) {
    vouchers.lockId(id).orElseThrow(() -> invalid("Không tìm thấy voucher.")).setEnabled(enabled);
  }

  @Transactional(readOnly=true)
  public BigDecimal preview(String code, BigDecimal gross, Long buyer, Reservation booking, DiningOrder order) {
    String normalized=normalize(code);
    if (normalized.isEmpty()) return BigDecimal.ZERO;
    var held=held(order);
    if (held!=null && normalized.equals(held.getCode())) return checkedDiscount(held.getMinimumTotal(), held.getDiscountType(),
        held.getDiscountValue(), held.getMaximumDiscount(), gross);
    var voucher=vouchers.findByCode(normalized).orElseThrow(() -> invalid("Mã voucher không tồn tại."));
    validate(voucher,booking==null ? buyer==null ? null : "buyer:"+buyer : customer(booking),gross);
    return discount(voucher.getDiscountType(),voucher.getDiscountValue(),voucher.getMaximumDiscount(),gross);
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public void change(DiningOrder order, Reservation booking, String code, BigDecimal gross) {
    String normalized=normalize(code);
    VoucherUsage previous=held(order);
    if (previous!=null && normalized.equals(previous.getCode())) {
      previous.reprice(checkedDiscount(previous.getMinimumTotal(),previous.getDiscountType(),previous.getDiscountValue(),
          previous.getMaximumDiscount(),gross));
      order.setVoucher(previous.getCode(),previous.getDiscountAmount());
      return;
    }
    if (normalized.isEmpty()) { release(order); return; }
    Voucher voucher=vouchers.lockCode(normalized).orElseThrow(() -> invalid("Mã voucher không tồn tại."));
    String customer=customer(booking);
    validate(voucher,customer,gross);
    var amount=discount(voucher.getDiscountType(),voucher.getDiscountValue(),voucher.getMaximumDiscount(),gross);
    if (previous!=null) {
      previous.release(now());
      usages.flush();
    }
    usages.save(new VoucherUsage(voucher,order.getId(),customer,amount,now()));
    order.setVoucher(voucher.getCode(),amount);
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public boolean reprice(DiningOrder order, BigDecimal gross) {
    var held=held(order);
    if (held==null) return false;
    if (gross.signum()<=0 || gross.compareTo(held.getMinimumTotal())<0
        || discount(held.getDiscountType(),held.getDiscountValue(),held.getMaximumDiscount(),gross).signum()==0) {
      release(order);
      return true;
    }
    var amount=discount(held.getDiscountType(),held.getDiscountValue(),held.getMaximumDiscount(),gross);
    held.reprice(amount);
    order.setVoucher(held.getCode(),amount);
    return false;
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public void release(DiningOrder order) {
    var held=held(order);
    if (held!=null) {
      held.release(now());
      order.setVoucher(null,null);
    }
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public void consume(DiningOrder order, boolean demo) {
    var held=held(order);
    if (held!=null) held.consume(now(),demo);
  }

  public boolean affected(DiningOrder order) { return order!=null && order.getId()!=null && usages.existsByOrderId(order.getId()); }

  public void guardDeposit(Reservation booking, BigDecimal next) {
    var remaining=next.subtract(booking.getDepositPaid()).max(BigDecimal.ZERO);
    var active=intents.findFirstByPaymentTypeAndReservationIdAndStatusInOrderByIdDesc(
        PaymentIntent.DEPOSIT,booking.getId(),List.of(PaymentIntent.PENDING,PaymentIntent.REVIEW_REQUIRED));
    if (active.filter(intent -> protectedIntent(intent) && intent.getAmount().compareTo(remaining)!=0).isPresent()) {
      throw invalid("Mã QR cọc đang hiệu lực hoặc chờ đối soát. Hoàn tất xử lý giao dịch trước khi đổi số tiền.");
    }
  }

  public void guardInvoice(DiningOrder order) {
    if (intents.findFirstByPaymentTypeAndOrderIdAndStatusInOrderByIdDesc(PaymentIntent.INVOICE,order.getId(),BLOCKED_QR)
        .filter(this::protectedIntent).isPresent()) {
      throw invalid("Hóa đơn có mã QR đang hiệu lực hoặc giao dịch đã nhận. Hãy xử lý giao dịch trước khi sửa voucher.");
    }
  }

  private boolean protectedIntent(PaymentIntent intent) {
    return !PaymentIntent.PENDING.equals(intent.getStatus()) || intent.getExpiresAt().isAfter(now());
  }

  private VoucherUsage held(DiningOrder order) {
    return order==null || order.getId()==null || order.getVoucherCode()==null ? null
        : usages.findByActiveOrderIdAndStatus(order.getId(),VoucherUsage.HELD).orElse(null);
  }

  private void validate(Voucher voucher,String customer,BigDecimal gross) {
    switch (voucher.state(now())) {
      case "DISABLED" -> throw invalid("Voucher đã bị vô hiệu hóa.");
      case "SCHEDULED" -> throw invalid("Voucher chưa đến thời gian sử dụng.");
      case "EXPIRED" -> throw invalid("Voucher đã hết hạn.");
      default -> { }
    }
    if (voucher.getTotalLimit()!=null && usages.allocated(voucher.getId())>=voucher.getTotalLimit()) {
      throw invalid("Voucher đã hết lượt sử dụng.");
    }
    if (voucher.getCustomerLimit()!=null) {
      if (customer==null) throw invalid("Voucher cần tài khoản hoặc số điện thoại khách đã ghi trên đặt bàn.");
      if (usages.allocatedFor(voucher.getId(),customer)>=voucher.getCustomerLimit()) {
        throw invalid("Khách đã dùng hết lượt cho voucher này.");
      }
    }
    checkedDiscount(voucher.getMinimumTotal(),voucher.getDiscountType(),voucher.getDiscountValue(),voucher.getMaximumDiscount(),gross);
  }

  private boolean hasRoom(Voucher v,String customer) {
    return (v.getTotalLimit()==null || usages.allocated(v.getId())<v.getTotalLimit())
        && (v.getCustomerLimit()==null || customer!=null && usages.allocatedFor(v.getId(),customer)<v.getCustomerLimit());
  }

  private BigDecimal checkedDiscount(BigDecimal minimum,String type,BigDecimal value,BigDecimal max,BigDecimal gross) {
    if (gross.signum()<=0 || gross.compareTo(minimum)<0) throw invalid("Giá trị món chưa đạt mức tối thiểu "+minimum.toPlainString()+" ₫.");
    var amount=discount(type,value,max,gross);
    if (amount.signum()==0) throw invalid("Voucher chưa tạo được khoản giảm trên giá trị món hiện tại.");
    return amount;
  }

  static BigDecimal discount(String type,BigDecimal value,BigDecimal max,BigDecimal gross) {
    var amount="PERCENT".equals(type) ? gross.multiply(value).movePointLeft(2) : value;
    if (max!=null) amount=amount.min(max);
    return amount.min(gross).max(BigDecimal.ZERO).setScale(0,RoundingMode.DOWN);
  }

  private String customer(Reservation booking) {
    if (booking.getCustomerId()!=null) return "buyer:"+booking.getCustomerId();
    String digits=Objects.toString(booking.getGuestPhone(),"").replaceAll("[^0-9]","");
    if (digits.startsWith("84") && digits.length()==11) digits="0"+digits.substring(2);
    if (digits.length()<8 || digits.length()>15) return null;
    try {
      var mac=Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret,"HmacSHA256"));
      return "guest:"+java.util.HexFormat.of().formatHex(mac.doFinal(("voucher:"+digits).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException(error); }
  }

  private String normalize(String code) {
    String value=Objects.toString(code,"").strip().toUpperCase(Locale.ROOT);
    if (!value.isEmpty() && !value.matches("[A-Z0-9][A-Z0-9_-]{1,31}")) throw invalid("Mã voucher phải có 2–32 ký tự chữ, số, gạch ngang hoặc gạch dưới.");
    return value;
  }

  private BookingException invalid(String message) { return new BookingException("voucherCode",message); }
  private LocalDateTime now() { return LocalDateTime.now(schedule.getClock()); }
}
