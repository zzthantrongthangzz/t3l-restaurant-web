package t3l.restaurant.t3l_restaurant.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import t3l.restaurant.t3l_restaurant.config.RestaurantInfo;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail.Kind;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail.Status;

@Service
public class ReservationEmailSender {
  private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");
  private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy 'lúc' HH:mm", VIETNAMESE);
  private final JavaMailSender mail;
  private final TemplateEngine templates;
  private final RestaurantInfo restaurant;
  private final String username;
  private final String websiteBase;
  private final boolean configured;

  public ReservationEmailSender(JavaMailSender mail, TemplateEngine templates, RestaurantInfo restaurant,
      @Value("${spring.mail.username:}") String username, @Value("${spring.mail.password:}") String password,
      @Value("${app.reservation-mail.public-base-url}") String websiteBase) {
    this.mail = mail;
    this.templates = templates;
    this.restaurant = restaurant;
    this.username = username.strip();
    this.websiteBase = validBase(websiteBase);
    this.configured = ReservationEmailQueue.mailbox(this.username) != null && !password.isBlank() && this.websiteBase != null;
  }

  public boolean isConfigured() {
    return configured;
  }

  public Result deliver(ReservationEmail email) {
    if (!configured) return new Result(Status.FAILED, "SMTP_NOT_CONFIGURED");
    MimeMessage message;
    try {
      Message text = copyFor(email);
      String dateTime = DATE_TIME.format(email.getStartsAt());
      String detailsUrl = websiteBase + UriComponentsBuilder.fromPath("/reservations/lookup")
          .queryParam("code", email.getBookingCode()).build().encode().toUriString();
      var context = new Context(VIETNAMESE);
      context.setVariable("email", email);
      context.setVariable("heading", text.heading());
      context.setVariable("summary", text.summary());
      context.setVariable("dateTime", dateTime);
      context.setVariable("detailsUrl", detailsUrl);
      context.setVariable("restaurant", restaurant);
      String html = templates.process("mail/reservation", context);

      message = mail.createMimeMessage();
      var helper = new MimeMessageHelper(message, true, "UTF-8");
      helper.setFrom(username, "T3L Restaurant");
      helper.setTo(email.getRecipient());
      helper.setSubject("T3L Restaurant | " + text.heading() + " · " + email.getBookingCode());
      helper.setText(plainText(email, text, dateTime, detailsUrl), html);
      message.saveChanges();
      message.setHeader("Message-ID", "<t3l-booking-" + email.getEventKey() + username.substring(username.indexOf('@')) + ">");
    } catch (MessagingException | IOException | RuntimeException exception) {
      return new Result(Status.FAILED, "MESSAGE_PREPARATION_FAILED");
    }

    try {
      mail.send(message);
      return new Result(Status.SENT, null);
    } catch (MailException exception) {
      return smtpFailure(exception);
    } catch (RuntimeException exception) {
      return new Result(Status.DELIVERY_UNKNOWN, "SMTP_DELIVERY_NOT_CONFIRMED");
    }
  }

  private String plainText(ReservationEmail email, Message text, String dateTime, String detailsUrl) {
    var body = new StringBuilder().append("T3L Restaurant\n\n").append(text.heading()).append("\n\n")
        .append("Chào ").append(email.getGuestName()).append(",\n").append(text.summary()).append("\n\n")
        .append("Mã đặt bàn: ").append(email.getBookingCode()).append("\n")
        .append("Ngày và giờ: ").append(dateTime).append("\n")
        .append("Số khách: ").append(email.getGuestCount()).append("\n")
        .append("Khu vực: ").append(email.getArea()).append("\n")
        .append("Trạng thái đặt bàn: ").append(email.getBookingStatus()).append("\n");
    if (email.getReviewNote() != null) body.append("Phản hồi từ T3L: ").append(email.getReviewNote()).append("\n");
    if (email.getDepositSummary() != null) body.append("Tiền cọc: ").append(email.getDepositSummary()).append("\n");
    body.append("\nXem chi tiết đặt bàn: ").append(detailsUrl).append("\n\n")
        .append("Liên hệ T3L Restaurant\n").append(restaurant.getAddress()).append("\n");
    if (!restaurant.getPhone().isBlank()) body.append(restaurant.getPhone()).append("\n");
    if (!restaurant.getEmail().isBlank()) body.append(restaurant.getEmail()).append("\n");
    return body.toString();
  }

  private static Result smtpFailure(MailException exception) {
    var causes = new ArrayDeque<Throwable>();
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    causes.add(exception);
    while (!causes.isEmpty()) {
      Throwable cause = causes.removeFirst();
      if (!visited.add(cause)) continue;
      if (cause instanceof MailAuthenticationException) return new Result(Status.FAILED, "SMTP_AUTHENTICATION_FAILED");
      if (cause instanceof MailConnectException || cause instanceof ConnectException
          || cause instanceof NoRouteToHostException || cause instanceof UnknownHostException) {
        return new Result(Status.RETRY, "SMTP_CONNECT_FAILED");
      }
      int reply = switch (cause) {
        case SMTPAddressFailedException rejectedAddress -> rejectedAddress.getReturnCode();
        case SMTPSendFailedException rejectedMessage -> rejectedMessage.getReturnCode();
        default -> 0;
      };
      if (reply >= 400 && reply < 600) return new Result(reply < 500 ? Status.RETRY : Status.FAILED, "SMTP_" + reply);
      if (cause.getCause() != null) causes.add(cause.getCause());
      if (cause instanceof MessagingException messaging && messaging.getNextException() != null) {
        causes.add(messaging.getNextException());
      }
      if (cause instanceof MailSendException failed) causes.addAll(Arrays.asList(failed.getMessageExceptions()));
    }
    return new Result(Status.DELIVERY_UNKNOWN, "SMTP_DELIVERY_NOT_CONFIRMED");
  }

  private static String validBase(String base) {
    try {
      URI uri = URI.create(base.strip());
      if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
          || (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))) return null;
      return uri.toASCIIString().replaceAll("/+$", "");
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static Message copyFor(ReservationEmail email) {
    if (email.getKind() == Kind.CONFIRMED && ReservationEmail.AWAITING_DEPOSIT.equals(email.getBookingStatus())) {
      return new Message("Đã duyệt giữ bàn, chờ tiền cọc",
          "Nhân viên đã duyệt chỗ cho bạn. Vui lòng xem chi tiết đặt bàn để gửi mã giao dịch sau khi chuyển khoản theo hướng dẫn của nhà hàng.");
    }
    return switch (email.getKind()) {
      case RECEIVED -> new Message("Đã tiếp nhận yêu cầu đặt bàn",
          "T3L đã nhận yêu cầu của bạn. Lịch đặt bàn đang chờ nhân viên kiểm tra. T3L sẽ gửi email riêng khi lịch được xác nhận chính thức.");
      case CONFIRMED -> new Message("Xác nhận đặt bàn chính thức",
          "T3L đã xác nhận đặt bàn của bạn. Hẹn gặp bạn vào ngày và giờ bên dưới.");
      case REJECTED -> new Message("Yêu cầu đặt bàn không được xác nhận",
          "T3L không thể xác nhận yêu cầu đặt bàn này. Bạn có thể chọn một khung giờ khác hoặc liên hệ nhà hàng để được hỗ trợ.");
      case CANCELLED -> new Message("Đặt bàn đã được hủy",
          "Nhân viên T3L đã hủy đặt bàn bên dưới. Vui lòng liên hệ nhà hàng nếu bạn cần hỗ trợ.");
      case RESCHEDULED -> new Message("Lịch đặt bàn đã được cập nhật",
          "Nhân viên T3L đã cập nhật ngày, giờ hoặc số khách cho đặt bàn của bạn. Vui lòng xem thông tin mới bên dưới.");
      case NO_SHOW -> new Message("Đã giải phóng bàn giữ chỗ",
          "Nhà hàng chưa ghi nhận bạn đến theo lịch đặt bàn bên dưới. Vui lòng liên hệ T3L nếu bạn cần hỗ trợ.");
      case CANCELLATION_APPROVED -> new Message("Đã xác nhận yêu cầu hủy đặt bàn",
          "Yêu cầu hủy của bạn đã được duyệt. Đặt bàn bên dưới đã được hủy.");
      case CHANGE_DECLINED -> new Message("Yêu cầu đổi lịch không được duyệt",
          "T3L không thể duyệt yêu cầu đổi lịch. Thông tin đặt bàn hiện tại được hiển thị bên dưới.");
      case CANCELLATION_DECLINED -> new Message("Yêu cầu hủy đặt bàn không được duyệt",
          "T3L không thể duyệt yêu cầu hủy. Thông tin và trạng thái đặt bàn hiện tại được hiển thị bên dưới.");
      case DEPOSIT_VERIFIED -> new Message("Đã đối soát tiền cọc",
          "T3L đã xác nhận giao dịch cọc. Xem chi tiết đặt bàn để kiểm tra số tiền đã nhận và tình trạng lượt đặt.");
      case DEPOSIT_REJECTED -> new Message("Giao dịch cọc chưa được xác nhận",
          "T3L chưa xác nhận được giao dịch. Vui lòng xem chi tiết để kiểm tra hạn gửi lại mã hoặc liên hệ nhà hàng.");
      case DEPOSIT_REFUNDED -> new Message("Đã ghi nhận hoàn tiền cọc",
          "Nhà hàng đã xác nhận việc hoàn cọc. Vui lòng xem chi tiết đặt bàn để kiểm tra số tiền.");
      case DEPOSIT_EXPIRED -> new Message("Đã giải phóng bàn do quá hạn cọc",
          "Thời hạn thanh toán hoặc đối soát đã hết. Nếu đã chuyển khoản, vui lòng xem chi tiết để theo dõi hoàn cọc.");
      case DEPOSIT_APPLIED -> new Message("Đã đối trừ tiền cọc vào hóa đơn",
          "Vui lòng xem chi tiết đặt bàn để kiểm tra tiền cọc đã khấu trừ và số tiền cần hoàn, nếu có.");
    };
  }

  private record Message(String heading, String summary) {}

  public record Result(Status status, String error) {}
}
