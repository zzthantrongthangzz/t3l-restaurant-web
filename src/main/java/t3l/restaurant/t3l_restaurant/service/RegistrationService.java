package t3l.restaurant.t3l_restaurant.service;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.dto.RegistrationForm;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

@Service
public class RegistrationService {
  private final UserAccountRepository accounts;
  private final PasswordEncoder encoder;
  private final Validator validator;

  public RegistrationService(UserAccountRepository accounts, PasswordEncoder encoder, Validator validator) {
    this.accounts = accounts;
    this.encoder = encoder;
    this.validator = validator;
  }

  @Transactional
  public UserAccount register(RegistrationForm form) {
    var violations = validator.validate(form);
    if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
    if (accounts.existsByUsernameIgnoreCase(form.getUsername())) {
      throw new DuplicateAccountException("username", "Tên đăng nhập đã được sử dụng.");
    }
    if (accounts.existsByEmailIgnoreCase(form.getEmail())) {
      throw new DuplicateAccountException("email", "Email đã được sử dụng.");
    }
    return accounts.saveAndFlush(UserAccount.customer(form.getFullName(), form.getEmail(), form.getPhone(),
        form.getUsername(), encoder.encode(form.getPassword())));
  }

  public static class DuplicateAccountException extends RuntimeException {
    private final String field;
    public DuplicateAccountException(String field, String message) { super(message); this.field = field; }
    public String getField() { return field; }
  }
}
