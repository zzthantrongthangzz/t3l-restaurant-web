package t3l.restaurant.t3l_restaurant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import t3l.restaurant.t3l_restaurant.config.RestaurantInfo;
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
