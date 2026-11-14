package t3l.restaurant.t3l_restaurant.document;

import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("faqs")
@Getter
public class FaqEntry {
  @Id private String id;
  private String question;
  private String answer;
  private int displayOrder;
  private boolean published;
}
