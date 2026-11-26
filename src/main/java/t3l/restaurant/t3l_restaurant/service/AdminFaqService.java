package t3l.restaurant.t3l_restaurant.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
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
  private final EmployeeActivityService activity;

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
      var faqId = new ObjectId();
      document.put("_id", faqId);
      activity.recordStandalone("Yêu cầu tạo FAQ", "FAQ", faqId.toHexString(), null);
      mongo.execute(FaqEntry.class, collection -> collection.insertOne(document));
      activity.recordStandalone("Tạo FAQ", "FAQ", faqId.toHexString(),
          "Đã thêm câu hỏi. " + state(form.getQuestion(), form.getDisplayOrder(), form.isPublished()));
      return;
    }
    activity.recordStandalone("Yêu cầu cập nhật FAQ", "FAQ", id, null);
    var old = update(id, new Update()
        .set("question", form.getQuestion())
        .set("answer", form.getAnswer())
        .set("displayOrder", form.getDisplayOrder())
        .set("published", form.isPublished()));
    activity.recordStandalone("Cập nhật FAQ", "FAQ", id,
        "Thông tin cũ: " + state(old.getQuestion(), old.getDisplayOrder(), old.isPublished())
            + " Thông tin mới: " + state(form.getQuestion(), form.getDisplayOrder(), form.isPublished())
            + (java.util.Objects.equals(old.getAnswer(), form.getAnswer()) ? "" : " Đã sửa câu trả lời."));
  }

  public void setPublished(String id, boolean published) {
    activity.recordStandalone("Yêu cầu đổi hiển thị FAQ", "FAQ", id,
        published ? "Yêu cầu hiển thị câu hỏi cho khách." : "Yêu cầu ẩn câu hỏi khỏi trang khách.");
    var old = update(id, new Update().set("published", published));
    if (old.isPublished() != published) activity.recordStandalone(published ? "Hiện FAQ" : "Ẩn FAQ", "FAQ", id,
        published ? "Câu hỏi đã hiển thị cho khách." : "Đã ẩn câu hỏi khỏi trang khách.");
  }

  private FaqEntry update(String id, Update changes) {
    var old = mongo.findAndModify(Query.query(Criteria.where("id").is(id)), changes, FaqEntry.class);
    if (old == null) throw notFound();
    return old;
  }

  private String state(String question, int order, boolean published) {
    return "“" + question + "” · vị trí " + order + (published ? " · đang hiển thị." : " · đang ẩn.");
  }

  private static BookingException notFound() {
    return new BookingException(null, "Không tìm thấy câu hỏi.");
  }
}
