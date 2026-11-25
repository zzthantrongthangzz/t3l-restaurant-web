package t3l.restaurant.t3l_restaurant.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import t3l.restaurant.t3l_restaurant.document.FaqEntry;
import t3l.restaurant.t3l_restaurant.dto.FaqForm;
import t3l.restaurant.t3l_restaurant.repository.FaqRepository;

@Service
@RequiredArgsConstructor
public class AdminFaqService {
  private final FaqRepository faqs;
  private final MongoTemplate mongo;

  public List<FaqEntry> list() {
    return faqs.findAll(Sort.by("displayOrder", "id"));
  }

  public FaqForm form(String id) {
    var faq = faqs.findById(id).orElseThrow(AdminFaqService::notFound);
    var form = new FaqForm();
    form.setQuestion(faq.getQuestion());
    form.setAnswer(faq.getAnswer());
    form.setDisplayOrder(faq.getDisplayOrder());
    form.setPublished(faq.isPublished());
    return form;
  }

  public void save(String id, FaqForm form) {
    if (id == null) {
      var faq = new FaqEntry(form.getQuestion(), form.getAnswer(), form.getDisplayOrder(), form.isPublished());
      var document = new Document();
      mongo.getConverter().write(faq, document);
      // Keep the same fields as the FAQ seed documents.
      document.remove(DefaultMongoTypeMapper.DEFAULT_TYPE_KEY);
      mongo.execute(FaqEntry.class, collection -> collection.insertOne(document));
      return;
    }
    update(id, new Update()
        .set("question", form.getQuestion())
        .set("answer", form.getAnswer())
        .set("displayOrder", form.getDisplayOrder())
        .set("published", form.isPublished()));
  }

  public void setPublished(String id, boolean published) {
    update(id, new Update().set("published", published));
  }

  private void update(String id, Update changes) {
    var result = mongo.updateFirst(Query.query(Criteria.where("id").is(id)), changes, FaqEntry.class);
    if (result.getMatchedCount() == 0) throw notFound();
  }

  private static BookingException notFound() {
    return new BookingException(null, "Không tìm thấy câu hỏi.");
  }
}
