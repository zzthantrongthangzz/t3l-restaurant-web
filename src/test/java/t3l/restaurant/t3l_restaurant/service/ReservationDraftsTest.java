package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;

class ReservationDraftsTest {
  @Test
  void guestDraftBelongsToTheFirstCustomerWhoResumesIt() {
    var drafts = new ReservationDrafts();
    var session = new MockHttpSession();
    var form = new ReservationForm();
    form.setNote("Private draft");
    String draft = drafts.save(session, form, null, null);

    assertEquals("Private draft", drafts.read(session, draft, 1L).getNote());
    assertNull(drafts.read(session, draft, 2L));
    assertNull(drafts.read(session, draft, null));
    assertNull(drafts.read(new MockHttpSession(), draft, 1L));

    form.setNote("Updated draft");
    assertNotEquals(draft, drafts.save(session, form, 2L, draft));
    assertEquals("Private draft", drafts.read(session, draft, 1L).getNote());
    assertEquals(draft, drafts.save(session, form, 1L, draft));
    assertEquals("Updated draft", drafts.read(session, draft, 1L).getNote());
  }
}
