package t3l.restaurant.t3l_restaurant.document;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("faqs")
@Getter
@NoArgsConstructor
public class FaqEntry {
  @Id private String id;
  private String question;
  private String answer;
  private int displayOrder;
  private boolean published;

  public FaqEntry(String question, String answer, int displayOrder, boolean published) {
    this.question = question;
    this.answer = answer;
    this.displayOrder = displayOrder;
    this.published = published;
  }
}
