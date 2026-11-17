package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import t3l.restaurant.t3l_restaurant.controller.ContactController;
import t3l.restaurant.t3l_restaurant.dto.ContactForm;

class ContactFailureTest {
  @Test void storageFailureNeverShowsSuccessAndPreservesTheForm() {
    var service = mock(ContactService.class); var form = new ContactForm();
    when(service.submit(form)).thenThrow(new DataAccessResourceFailureException("Database unavailable"));
    var errors = new BeanPropertyBindingResult(form, "contactForm");
    var model = new ExtendedModelMap(); var redirect = new RedirectAttributesModelMap();
    var view = new ContactController(service).submit(form, errors, model, redirect, new MockHttpServletResponse());
    assertEquals("web/contact", view); assertTrue(errors.hasGlobalErrors());
    assertTrue(errors.getGlobalError().getDefaultMessage().contains("Chưa thể lưu"));
    assertTrue(redirect.getFlashAttributes().isEmpty());
  }
}
