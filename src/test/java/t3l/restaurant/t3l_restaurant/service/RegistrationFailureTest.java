package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import t3l.restaurant.t3l_restaurant.controller.RegistrationController;
import t3l.restaurant.t3l_restaurant.dto.RegistrationForm;

class RegistrationFailureTest {
  @Test void databaseFailureAndConcurrentConflictNeverReportSuccess() {
    for (var exception : new RuntimeException[] {new DataAccessResourceFailureException("unavailable"), new DataIntegrityViolationException("duplicate")}) {
      var service = mock(RegistrationService.class); var form = new RegistrationForm();
      when(service.register(form)).thenThrow(exception);
      var errors = new BeanPropertyBindingResult(form, "registrationForm");
      var redirect = new RedirectAttributesModelMap();
      var view = new RegistrationController(service).submit(form, errors, redirect, new MockHttpServletResponse());
      assertEquals("auth/register", view); assertTrue(errors.hasGlobalErrors());
      assertTrue(redirect.getFlashAttributes().isEmpty());
    }
  }
}
