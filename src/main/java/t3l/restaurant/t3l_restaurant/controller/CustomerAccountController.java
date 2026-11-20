package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.CustomerAddressForm;
import t3l.restaurant.t3l_restaurant.dto.CustomerProfileForm;
import t3l.restaurant.t3l_restaurant.dto.InvoiceCompanyForm;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.security.GoogleOAuthFlow;
import t3l.restaurant.t3l_restaurant.service.CustomerAccountService;
import t3l.restaurant.t3l_restaurant.service.CustomerAccountService.DuplicateEmailException;
import t3l.restaurant.t3l_restaurant.service.PhoneCustomerService;
import t3l.restaurant.t3l_restaurant.service.ReservationService;

@Controller
@RequestMapping("/account")
public class CustomerAccountController {
  private static final String ACCOUNT = "redirect:/account";
  private static final String ADDRESSES = "redirect:/account/addresses";
  private final CustomerAccountService customers;
  private final ReservationService reservations;
  private final GoogleOAuthFlow google;

  public CustomerAccountController(CustomerAccountService customers, ReservationService reservations,
      GoogleOAuthFlow google) {
    this.customers = customers;
    this.reservations = reservations;
    this.google = google;
  }

  @ModelAttribute
  void privateResponse(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
  }

  @GetMapping
  String account(Authentication authentication, Model model) {
    return renderProfile(customers.signedInCustomer(authentication), model);
  }

  @PostMapping
  String account(@Valid @ModelAttribute("profileForm") CustomerProfileForm form, BindingResult errors,
      Authentication authentication, Model model, RedirectAttributes redirect) {
    UserAccount account = customers.signedInCustomer(authentication);
    if (!errors.hasErrors()) {
      try {
        customers.updateProfile(account.getId(), form);
        redirect.addFlashAttribute("accountNotice", "Đã lưu thông tin tài khoản.");
        return ACCOUNT;
      } catch (DuplicateEmailException | DataIntegrityViolationException exception) {
        errors.rejectValue("email", "email.duplicate", "Email đã được sử dụng.");
      }
    }
    return renderProfile(account, model);
  }

  @PostMapping("/google/link")
  void googleLink(Authentication authentication,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    UserAccount account = customers.signedInCustomer(authentication);
    if (customers.profile(account).getGoogleSubject() != null) {
      response.sendRedirect(request.getContextPath() + "/account?google=already-linked");
    } else if (!google.startLink(account, request, response)) {
      response.sendRedirect(request.getContextPath() + "/account?google=failed");
    }
  }

  @GetMapping("/reservations")
  String reservations(@RequestParam(defaultValue = "upcoming") String tab, Authentication authentication,
      Model model) {
    boolean past = "past".equals(tab);
    model.addAttribute("tab", past ? "past" : "upcoming");
    model.addAttribute("bookings", reservations.customerBookings(customers.signedInCustomer(authentication).getId(), past));
    return page(model, "reservations", "Thông tin đặt bàn");
  }

  @GetMapping("/orders")
  String orders(Model model) {
    return page(model, "orders", "Đơn hàng");
  }

  @GetMapping("/vouchers")
  String vouchers(@RequestParam(defaultValue = "available") String tab, Model model) {
    model.addAttribute("tab", switch (tab) {
      case "used", "expired" -> tab;
      default -> "available";
    });
    return page(model, "vouchers", "Ví voucher");
  }

  @GetMapping("/addresses")
  String addresses(Authentication authentication, Model model) {
    return renderAddresses(customers.signedInCustomer(authentication).getId(), null, model);
  }

  @GetMapping("/addresses/new")
  String addAddress(Authentication authentication, Model model) {
    model.addAttribute("addressForm", new CustomerAddressForm());
    return renderAddresses(customers.signedInCustomer(authentication).getId(), null, model);
  }

  @GetMapping("/addresses/{id}")
  String editAddress(@PathVariable Long id, Authentication authentication, Model model) {
    Long accountId = customers.signedInCustomer(authentication).getId();
    model.addAttribute("addressForm", customers.addressForm(customers.address(accountId, id)));
    return renderAddresses(accountId, id, model);
  }

  @PostMapping({"/addresses/new", "/addresses/{id}"})
  String saveAddress(@PathVariable(required = false) Long id,
      @Valid @ModelAttribute("addressForm") CustomerAddressForm form, BindingResult errors,
      Authentication authentication, Model model, RedirectAttributes redirect) {
    Long accountId = customers.signedInCustomer(authentication).getId();
    if (errors.hasErrors()) {
      if (id != null) customers.address(accountId, id);
      return renderAddresses(accountId, id, model);
    }
    customers.saveAddress(accountId, id, form);
    redirect.addFlashAttribute("accountNotice", "Đã lưu địa chỉ.");
    return ADDRESSES;
  }

  @PostMapping("/addresses/{id}/default")
  String defaultAddress(@PathVariable Long id, Authentication authentication, RedirectAttributes redirect) {
    customers.makeDefault(customers.signedInCustomer(authentication).getId(), id);
    redirect.addFlashAttribute("accountNotice", "Đã cập nhật địa chỉ mặc định.");
    return ADDRESSES;
  }

  @PostMapping("/addresses/{id}/delete")
  String deleteAddress(@PathVariable Long id, Authentication authentication, RedirectAttributes redirect) {
    customers.deleteAddress(customers.signedInCustomer(authentication).getId(), id);
    redirect.addFlashAttribute("accountNotice", "Đã xóa địa chỉ.");
    return ADDRESSES;
  }

  @GetMapping("/invoices")
  String invoices(@RequestParam(defaultValue = "list") String tab, Authentication authentication, Model model) {
    UserAccount account = customers.signedInCustomer(authentication);
    boolean company = "company".equals(tab);
    model.addAttribute("companyForm", customers.companyForm(customers.profile(account)));
    return invoicePage(model, company);
  }

  @PostMapping("/invoices/company")
  String company(@Valid @ModelAttribute("companyForm") InvoiceCompanyForm form, BindingResult errors,
      Authentication authentication, Model model, RedirectAttributes redirect) {
    if (errors.hasErrors()) return invoicePage(model, true);
    customers.updateCompany(customers.signedInCustomer(authentication).getId(), form);
    redirect.addAttribute("tab", "company");
    redirect.addFlashAttribute("accountNotice", "Đã lưu thông tin công ty.");
    return "redirect:/account/invoices";
  }

  private String renderProfile(UserAccount account, Model model) {
    var profile = customers.profile(account);
    if (!model.containsAttribute("profileForm")) {
      model.addAttribute("profileForm", customers.profileForm(account, profile));
    }
    model.addAttribute("linkedGoogleEmail", profile.getGoogleEmail());
    model.addAttribute("verifiedPhone", account.getVerifiedPhone());
    model.addAttribute("localCustomer", PhoneCustomerService.isLocal(account));
    return page(model, "profile", "Tài khoản");
  }

  private String renderAddresses(Long accountId, Long id, Model model) {
    model.addAttribute("addresses", customers.addresses(accountId));
    model.addAttribute("addressId", id);
    return page(model, "addresses", "Địa chỉ");
  }

  private String invoicePage(Model model, boolean company) {
    model.addAttribute("tab", company ? "company" : "list");
    return page(model, "invoices", "Hóa đơn");
  }

  private String page(Model model, String section, String title) {
    model.addAttribute("section", section);
    model.addAttribute("pageTitle", title);
    return "account/panel";
  }
}
