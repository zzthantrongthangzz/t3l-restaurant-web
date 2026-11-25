package t3l.restaurant.t3l_restaurant.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PageFragments implements WebMvcConfigurer {
  private static final String FRAGMENT_HEADER = "X-T3L-Fragment";
  private static final Set<String> FILTER_VIEWS = Set.of(
      "staff/reservations", "staff/tables", "account/panel",
      "admin/accounts", "admin/activity", "admin/contacts", "admin/inventory",
      "admin/invoices", "admin/revenue", "admin/shifts", "admin/analytics", "admin/vouchers");

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HandlerInterceptor() {
      @Override
      public void postHandle(HttpServletRequest request, HttpServletResponse response,
          Object handler, ModelAndView view) {
        if (view == null || !"GET".equals(request.getMethod())) return;
        String name = view.getViewName();
        if (name == null || !FILTER_VIEWS.contains(name)) return;
        response.addHeader("Vary", FRAGMENT_HEADER);
        if ("main".equals(request.getHeader(FRAGMENT_HEADER))) {
          view.setViewName(name + " :: main");
          response.setHeader(FRAGMENT_HEADER, "main");
        }
      }
    });
  }
}
