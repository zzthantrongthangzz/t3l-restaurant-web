package t3l.restaurant.t3l_restaurant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoTimeoutException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import t3l.restaurant.t3l_restaurant.config.RestaurantInfo;
import t3l.restaurant.t3l_restaurant.document.FaqEntry;
import t3l.restaurant.t3l_restaurant.entity.Dish;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;
import t3l.restaurant.t3l_restaurant.repository.FaqRepository;
import t3l.restaurant.t3l_restaurant.service.ReservationSchedule;
import t3l.restaurant.t3l_restaurant.service.ReservationService;

class ChatTest {
  private final GeminiChat gemini = mock(GeminiChat.class);
  private final ChatVectorIndex index = mock(ChatVectorIndex.class);
  private final RestaurantInfo restaurant = mock(RestaurantInfo.class);
  private final FaqRepository faqs = mock(FaqRepository.class);
  private final ReservationSchedule schedule = mock(ReservationSchedule.class);
  private final ChatKnowledge knowledge = new ChatKnowledge(faqs, restaurant, schedule, index, gemini,
      new ChatSettings("", "test", "test", 768, 2, 10, 8, ""));
  private final ReservationService reservations = mock(ReservationService.class);
  private final DishRepository dishes = mock(DishRepository.class);

  @Test
  void carriesBookingContextAndHandlesMorningWithoutMistakingThePronounForEvening() {
    var today = LocalDate.of(2026, 11, 20);
    var first = ChatIntent.parse("Tôi muốn đặt bàn lúc 10h ngày mai", null, today);
    var second = ChatIntent.parse("4 khách", ChatIntent.stored(first.context()), today);
    assertThat(second.kind()).isEqualTo(ChatIntent.Kind.DRAFT);
    assertThat(second.date()).isEqualTo(today.plusDays(1));
    assertThat(second.time()).isEqualTo(LocalTime.of(10, 0));
    assertThat(second.guests()).isEqualTo(4);
    assertThat(ChatIntent.parse("Bàn trống 7h tối ngày mai cho 2 người?", null, today).time())
        .isEqualTo(LocalTime.of(19, 0));
    assertThat(ChatIntent.parse("Đặt bàn ngày mai 19h 30 cho 4 khách", null, today).time())
        .isEqualTo(LocalTime.of(19, 30));
    assertThat(ChatIntent.parse("Đặt bàn ngày mai 19 giờ 4 khách", null, today).time())
        .isEqualTo(LocalTime.of(19, 0));
    assertThat(ChatIntent.parse("Giờ mở cửa ngày mai?", first, today).kind()).isEqualTo(ChatIntent.Kind.INFO);
    assertThat(ChatIntent.parse("Tra cứu giờ mở cửa", first, today).kind()).isEqualTo(ChatIntent.Kind.INFO);
  }

  @Test
  void invalidBookingChangesNeverReuseTheOldValueOrConsultTheModel() {
    when(schedule.today()).thenReturn(LocalDate.of(2026, 11, 20));
    var assistant = new ChatAssistant(gemini, knowledge, dishes, reservations, schedule);
    var first = assistant.reply("Đặt bàn ngày mai 18h cho 4 khách", List.of(), null);
    assertThat(first.reply().draft()).isTrue();
    clearInvocations(reservations);
    for (String change : List.of("19h99", "19:", "999:00", "31/02/2027", "Đặt bàn -2 khách", "Đặt bàn 2,5 khách",
        "Đặt bàn 10000 khách")) {
      var corrected = assistant.reply(change, List.of(first.turn()), null);
      assertThat(corrected.reply().draft()).as(change).isFalse();
    }
    var invalid = assistant.reply("31/02/2027 19h", List.of(first.turn()), null);
    assertThat(assistant.reply("20h", List.of(invalid.turn()), null).reply().draft()).isFalse();
    verifyNoInteractions(reservations);
    verify(gemini, never()).structured(anyString(), any(), anyMap());
  }

  @Test
  void usesPublicLiveMenuPricesForTotalBudgetAndDiet() {
    var drink = new Dish();
    drink.update("Nước suối", "", BigDecimal.valueOf(20000), null, null, "", "Đồ uống", true);
    when(dishes.findByAvailableTrueOrderByCategoryNameAscNameAsc()).thenReturn(List.of(
        dish("Salad", "Chay", 75_000), dish("Steak", "", 50_000), dish("Nấm nướng", "Chay", 180_000), drink));
    var assistant = new ChatAssistant(gemini, knowledge, dishes, reservations, schedule);
    var answer = assistant.reply("Gợi ý món chay cho 2 người, ngân sách 200k", List.of(), null).reply();
    assertThat(answer.dishes()).containsExactly(new ChatAssistant.DishCard("Salad", BigDecimal.valueOf(75_000), 2));
    assertThat(answer.links()).containsExactly("/menu");
    assertThat(assistant.reply("Gợi ý món", List.of(), null).reply().dishes())
        .extracting(ChatAssistant.DishCard::name).doesNotContain("Nước suối");
    assertThat(assistant.reply("Gợi ý đồ uống", List.of(), null).reply().dishes())
        .extracting(ChatAssistant.DishCard::name).containsExactly("Nước suối");
    verifyNoInteractions(reservations);
    assertThat(assistant.reply("Món gì cho khách dị ứng tôm?", List.of(), null).reply().dishes()).isEmpty();
    assertThat(assistant.reply("Tôi dị ứng đậu phộng", List.of(), null).reply().text()).contains("dị ứng");
    for (String amount : List.of("200.000 ₫", "200.000 đồng", "200000 VND", "0,2 triệu")) {
      var result = assistant.reply("Gợi ý món chay cho 2 người, ngân sách " + amount, List.of(), null);
      assertThat(result.reply().dishes()).as(amount).extracting(ChatAssistant.DishCard::name).containsExactly("Salad");
    }
    for (String amount : List.of("0k", "-200k", "9.999.999.999 đồng")) {
      var result = assistant.reply("Gợi ý món với " + amount, List.of(), null);
      assertThat(result.reply().dishes()).as(amount).isEmpty();
      assertThat(result.reply().text()).contains("ngân sách hợp lệ");
    }
  }

  @Test
  void personalLookupsNeverSendCodesToGeminiOrStoreResults() {
    var owner = mock(UserAccount.class);
    when(owner.getId()).thenReturn(12L);
    var assistant = new ChatAssistant(gemini, knowledge, dishes, reservations, schedule);
    var guest = assistant.reply("Tra cứu T3L-ABCD1234", List.of(), null);
    assertThat(guest.reply().links()).containsExactly("/login");
    var buyer = assistant.reply("Tra cứu T3L-ABCD1234", List.of(), owner);
    assertThat(buyer.reply().text()).contains("không tìm thấy");
    assertThat(buyer.turn().user()).doesNotContain("ABCD1234");
    verify(reservations).findVisibleByCode("T3L-ABCD1234", owner, false);
    verifyNoInteractions(gemini);
    assertThat(ChatIntent.safe("0901 234 567 test@example.com T3L-ABCD1234")).doesNotContain("0901", "example", "ABCD");
    assertThat(ChatIntent.safe("Toi ten:Nguyen Van A, xin chào")).doesNotContain("Nguyen Van A");
  }

  @Test
  void answersFromPublishedFaqDuringVectorOrGeminiFailureAndAdmitsUnknown() {
    hours();
    when(schedule.today()).thenReturn(LocalDate.of(2026, 11, 20));
    var faq = mock(FaqEntry.class);
    when(faq.getId()).thenReturn("parking");
    when(faq.getQuestion()).thenReturn("Nhà hàng có bãi đỗ xe không?");
    when(faq.getAnswer()).thenReturn("Có bãi đỗ xe bên cạnh nhà hàng.");
    when(faqs.findByPublishedTrueOrderByDisplayOrderAsc()).thenReturn(List.of(faq));
    when(gemini.configured()).thenReturn(true);
    when(gemini.structured(anyString(), any(), anyMap())).thenThrow(new GeminiChat.Unavailable());
    when(index.ready()).thenThrow(new MongoTimeoutException("offline")).thenReturn(true);
    when(gemini.embedding(anyString(), eq(true))).thenThrow(new GeminiChat.Unavailable());
    var assistant = new ChatAssistant(gemini, knowledge, dishes, reservations, schedule);
    assertThat(assistant.reply("Có bãi đỗ xe không?", List.of(), null).reply().text())
        .isEqualTo("Có bãi đỗ xe bên cạnh nhà hàng.");
    assertThat(assistant.reply("xyzzy quux?", List.of(), null).reply().text()).contains("chưa tìm được");
  }

  @Test
  void modelCannotTurnUnknownInputIntoAGreetingOrPersonalLookup() {
    hours();
    when(schedule.today()).thenReturn(LocalDate.of(2026, 11, 20));
    when(gemini.configured()).thenReturn(true);
    var json = new ObjectMapper();
    when(gemini.structured(anyString(), any(), anyMap()))
        .thenReturn(json.createObjectNode().put("intent", "GREETING"), json.createObjectNode().put("intent", "LOOKUP"));
    var assistant = new ChatAssistant(gemini, knowledge, dishes, reservations, schedule);
    var customer = mock(UserAccount.class);
    for (int attempt = 0; attempt < 2; attempt++) {
      assertThat(assistant.reply("xyzzy quux?", List.of(), customer).reply().text()).contains("chưa tìm được");
    }
    verifyNoInteractions(reservations);
  }

  @Test
  void vectorHitsMustMatchCurrentSourceAndModelQuotesMustBeVerifiable() throws Exception {
    hours();
    when(gemini.configured()).thenReturn(true);
    when(index.ready()).thenReturn(true);
    List<Double> vector = List.of(1d, 0d);
    when(gemini.embedding(anyString(), eq(true))).thenReturn(vector);
    var hours = knowledge.corpus().getFirst();
    when(index.search(vector)).thenReturn(List.of(new ChatVectorIndex.Hit(hours.id(), "stale", 0.99)));
    assertThat(knowledge.answer("Giờ mở cửa", List.of()).text()).contains("11:00");
    verify(gemini, never()).structured(anyString(), any(), anyMap());

    when(index.search(vector)).thenReturn(List.of(new ChatVectorIndex.Hit(hours.id(), hours.hash(), 0.99)));
    var output = new ObjectMapper().createObjectNode();
    output.putArray("quotes").add("Mở cửa lúc 02:00 mỗi ngày.").add("T3L Restaurant phục vụ từ 11:00 đến 21:00.");
    when(gemini.structured(anyString(), any(), anyMap())).thenReturn(output);
    assertThat(knowledge.answer("Bên mình hoạt động lúc nào?", List.of()).text())
        .isEqualTo("T3L Restaurant phục vụ từ 11:00 đến 21:00.");
  }

  @Test
  void syncEmbedsOnlyNewOrChangedDocumentsAndPrunesRemovedSources() {
    hours();
    var current = knowledge.corpus();
    var changed = current.getFirst();
    when(index.versions()).thenReturn(java.util.stream.Stream.concat(current.stream().map(source ->
        new Document("_id", source.id()).append("hash", source.id().equals(changed.id()) ? "outdated" : source.hash())),
        java.util.stream.Stream.of(new Document("_id", "faq:removed").append("hash", "old"))).toList());
    when(gemini.embedding(changed.text(), false)).thenReturn(List.of(1d));
    knowledge.syncOnce();
    verify(index).ensureIndex();
    verify(index).prune(java.util.Set.of("faq:removed"));
    verify(gemini, times(1)).embedding(anyString(), eq(false));
    verify(index).upsert(changed.id(), changed.hash(), changed.source(), changed.question(), changed.answer(), List.of(1d));
  }

  private void hours() {
    when(schedule.rules()).thenReturn(new ReservationSchedule.Rules(
        LocalTime.of(11, 0), LocalTime.of(21, 0), 120, 30, 90, 30, 15));
  }

  private Dish dish(String name, String label, long price) {
    var dish = new Dish();
    dish.update(name, "", BigDecimal.valueOf(price), null, null, label, "Món chính", true);
    return dish;
  }
}
