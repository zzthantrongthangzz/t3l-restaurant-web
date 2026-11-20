package t3l.restaurant.t3l_restaurant.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import t3l.restaurant.t3l_restaurant.dto.CustomerAddressForm;
import t3l.restaurant.t3l_restaurant.dto.CustomerProfileForm;
import t3l.restaurant.t3l_restaurant.dto.InvoiceCompanyForm;
import t3l.restaurant.t3l_restaurant.entity.CustomerAddress;
import t3l.restaurant.t3l_restaurant.entity.CustomerProfile;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.CustomerAddressRepository;
import t3l.restaurant.t3l_restaurant.repository.CustomerProfileRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

@Service
public class CustomerAccountService {
  private final UserAccountRepository accounts;
  private final CustomerProfileRepository profiles;
  private final CustomerAddressRepository addresses;

  public CustomerAccountService(UserAccountRepository accounts, CustomerProfileRepository profiles,
      CustomerAddressRepository addresses) {
    this.accounts = accounts;
    this.profiles = profiles;
    this.addresses = addresses;
  }

  public UserAccount signedInCustomer(Authentication authentication) {
    if (authentication == null || authentication.getAuthorities().stream()
        .noneMatch(authority -> authority.getAuthority().equals("ROLE_BUYER"))) {
      return null;
    }
    return accounts.findByUsername(authentication.getName()).filter(CustomerAccountService::isCustomer)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
  }

  static boolean isCustomer(UserAccount account) {
    return account.isEnabled() && "BUYER".equals(account.getRole());
  }

  public CustomerProfile profile(UserAccount account) {
    return profiles.findById(account.getId()).orElseGet(() -> new CustomerProfile(account));
  }

  public CustomerProfileForm profileForm(UserAccount account, CustomerProfile profile) {
    var form = new CustomerProfileForm();
    String fullName = account.getFullName();
    if (profile.getGivenName() != null) {
      form.setFamilyName(profile.getFamilyName());
      form.setGivenName(profile.getGivenName());
    } else if (fullName != null && !fullName.isBlank()) {
      int split = fullName.lastIndexOf(' ');
      if (split < 0) {
        form.setGivenName(fullName);
      } else {
        form.setFamilyName(fullName.substring(0, split));
        form.setGivenName(fullName.substring(split + 1));
      }
    }
    form.setEmail(account.getEmail());
    form.setPhone(account.getPhone());
    form.setDietaryPreferences(profile.getDietaryPreferences());
    form.setBookingNotifications(profile.isBookingNotifications());
    form.setMarketingOptIn(profile.isMarketingOptIn());
    return form;
  }

  @Transactional
  public void updateProfile(Long accountId, CustomerProfileForm form) {
    UserAccount account = lockCustomer(accountId);
    String email = form.getEmail();
    if (email != null && !email.isBlank() && (accounts.existsByEmailIgnoreCaseAndIdNot(email, accountId)
        || profiles.existsByGoogleEmailIgnoreCaseAndIdNot(email, accountId))) {
      throw new DuplicateEmailException();
    }
    CustomerProfile profile = profile(account);
    profile.updateProfile(form);
    profiles.saveAndFlush(profile);
  }

  public boolean needsBookingProfile(UserAccount account) {
    return account.getFullName() == null || account.getFullName().isBlank()
        || bookingPhone(account.getPhone()) == null;
  }

  public String bookingPhone(String phone) {
    if (phone == null || !phone.strip().matches("\\+?[0-9 .()\\-]+")) return null;
    String digits = phone.replaceAll("[^0-9]", "");
    if (digits.length() < 8 || digits.length() > 15) return null;
    String vietnamese = SmsOtpService.normalizePhone(phone);
    return vietnamese != null ? vietnamese : phone.strip().startsWith("+") ? "+" + digits : digits;
  }

  public static class DuplicateEmailException extends RuntimeException {}

  public List<CustomerAddress> addresses(Long accountId) {
    return addresses.findByAccount_IdOrderByDefaultAddressDescIdAsc(accountId);
  }

  public CustomerAddress address(Long accountId, Long id) {
    return addresses.findByIdAndAccount_Id(id, accountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  public CustomerAddressForm addressForm(CustomerAddress address) {
    var form = new CustomerAddressForm();
    form.setRecipientName(address.getRecipientName());
    form.setPhone(address.getPhone());
    form.setStreetAddress(address.getStreetAddress());
    form.setDefaultAddress(address.isDefaultAddress());
    return form;
  }

  @Transactional
  public void saveAddress(Long accountId, Long id, CustomerAddressForm form) {
    UserAccount account = lockCustomer(accountId);
    CustomerAddress address = id == null ? new CustomerAddress(account) : address(accountId, id);
    var saved = addresses(accountId);
    address.update(form);
    if (form.isDefaultAddress() || saved.isEmpty()) {
      saved.forEach(item -> item.setDefaultAddress(false));
      address.setDefaultAddress(true);
    }
    addresses.save(address);
  }

  @Transactional
  public void makeDefault(Long accountId, Long id) {
    lockCustomer(accountId);
    var address = address(accountId, id);
    addresses(accountId).forEach(item -> item.setDefaultAddress(false));
    address.setDefaultAddress(true);
  }

  @Transactional
  public void deleteAddress(Long accountId, Long id) {
    lockCustomer(accountId);
    var address = address(accountId, id);
    if (address.isDefaultAddress()) {
      addresses(accountId).stream().filter(item -> !id.equals(item.getId()))
          .findFirst().ifPresent(item -> item.setDefaultAddress(true));
    }
    addresses.delete(address);
  }

  public InvoiceCompanyForm companyForm(CustomerProfile profile) {
    var form = new InvoiceCompanyForm();
    form.setCompanyName(profile.getCompanyName());
    form.setTaxCode(profile.getCompanyTaxCode());
    form.setCompanyAddress(profile.getCompanyAddress());
    form.setInvoiceEmail(profile.getInvoiceEmail());
    return form;
  }

  @Transactional
  public void updateCompany(Long accountId, InvoiceCompanyForm form) {
    CustomerProfile profile = profile(lockCustomer(accountId));
    profile.updateCompany(form);
    profiles.save(profile);
  }

  private UserAccount lockCustomer(Long id) {
    return accounts.lockById(id).filter(CustomerAccountService::isCustomer)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
  }
}
