package t3l.restaurant.t3l_restaurant.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.DishForm;
import t3l.restaurant.t3l_restaurant.service.AdminInventoryService;
import t3l.restaurant.t3l_restaurant.service.DishService;

@Controller
@RequestMapping("/admin/dishes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
  private final DishService dishes;
  private final AdminInventoryService inventory;

  @GetMapping
  public String list() {
    return "redirect:/menu";
  }

  @GetMapping("/new")
  public String createForm(Model model) {
    model.addAttribute("form", new DishForm());
    return formPage(null, model);
  }

  @GetMapping("/{id}/edit")
  public String editForm(@PathVariable Long id, Model model) {
    model.addAttribute("form", dishes.toForm(dishes.findById(id)));
    return formPage(id, model);
  }

  @PostMapping({"", "/{id}"})
  public String save(@PathVariable(required = false) Long id, @Valid @ModelAttribute("form") DishForm form,
      BindingResult result, Model model, RedirectAttributes redirect) {
    if (!result.hasErrors()) {
      try {
        dishes.save(id, form);
        redirect.addFlashAttribute("notice", id == null ? "Đã thêm món ăn." : "Đã cập nhật món ăn.");
        return "redirect:/menu";
      } catch (IllegalArgumentException | IllegalStateException exception) {
        result.reject("dish.invalid", exception.getMessage());
      } catch (DataIntegrityViolationException exception) {
        result.reject("dish.duplicate", "Tên món đã được sử dụng hoặc món vẫn đang có giao dịch liên quan.");
      }
    }
    return formPage(id, model);
  }

  @PostMapping("/{id}/state")
  public String state(@PathVariable Long id, @RequestParam boolean value, @RequestParam(defaultValue = "false") boolean stock,
      RedirectAttributes redirect) {
    return update(() -> dishes.setMenuState(id, value, stock), "Đã cập nhật trạng thái món.", redirect);
  }

  @PostMapping("/{id}/delete")
  public String delete(@PathVariable Long id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
    return update(() -> dishes.delete(id, reason), "Đã xóa món ăn.", redirect);
  }

  private String formPage(Long id, Model model) {
    model.addAttribute("dishId", id);
    model.addAttribute("heading", id == null ? "Thêm món ăn" : "Cập nhật món ăn");
    model.addAttribute("currentImageUrl", id == null ? null : dishes.findById(id).getImageUrl());
    model.addAttribute("categories", inventory.sectionList("MENU"));
    return "admin/dish-form";
  }

  private String update(Runnable operation, String notice, RedirectAttributes redirect) {
    try {
      operation.run();
      redirect.addFlashAttribute("notice", notice);
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    } catch (DataIntegrityViolationException exception) {
      redirect.addFlashAttribute("error", "Món có lịch sử bán. Hãy ẩn món để giữ lại dữ liệu.");
    }
    return "redirect:/menu";
  }
}
