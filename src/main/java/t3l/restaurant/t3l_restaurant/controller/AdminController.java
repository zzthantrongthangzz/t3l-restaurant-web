package t3l.restaurant.t3l_restaurant.controller;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.DishForm;
import t3l.restaurant.t3l_restaurant.service.DishService;

@Controller
@RequestMapping("/admin/dishes")
public class AdminController {
  private final DishService dishes;

  public AdminController(DishService dishes) {
    this.dishes = dishes;
  }

  @GetMapping
  public String list(Model model) {
    model.addAttribute("dishes", dishes.findAll());
    return "admin/dishes";
  }

  @GetMapping("/new")
  public String createForm(Model model) {
    model.addAttribute("form", new DishForm());
    model.addAttribute("heading", "Thêm món ăn");
    return "admin/dish-form";
  }

  @GetMapping("/{id}/edit")
  public String editForm(@PathVariable Long id, Model model) {
    model.addAttribute("form", dishes.toForm(dishes.findById(id)));
    model.addAttribute("dishId", id);
    model.addAttribute("currentImageUrl", dishes.findById(id).getImageUrl());
    model.addAttribute("heading", "Cập nhật món ăn");
    return "admin/dish-form";
  }

  @PostMapping
  public String create(
      @Valid @ModelAttribute("form") DishForm form,
      BindingResult result,
      Model model,
      RedirectAttributes redirect) {
    if (form.getImage() == null || form.getImage().isEmpty()) {
      result.rejectValue("image", "image.required", "Vui lòng chọn ảnh món ăn.");
    }
    if (result.hasErrors()) {
      model.addAttribute("heading", "Thêm món ăn");
      return "admin/dish-form";
    }
    try {
      dishes.save(null, form);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      result.rejectValue("image", "image.upload", exception.getMessage());
      model.addAttribute("heading", "Thêm món ăn");
      return "admin/dish-form";
    }
    redirect.addFlashAttribute("notice", "Đã thêm món ăn.");
    return "redirect:/admin/dishes";
  }

  @PostMapping("/{id}")
  public String update(
      @PathVariable Long id,
      @Valid @ModelAttribute("form") DishForm form,
      BindingResult result,
      Model model,
      RedirectAttributes redirect) {
    if (result.hasErrors()) {
      model.addAttribute("heading", "Cập nhật món ăn");
      model.addAttribute("dishId", id);
      return "admin/dish-form";
    }
    try {
      dishes.save(id, form);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      result.rejectValue("image", "image.upload", exception.getMessage());
      model.addAttribute("heading", "Cập nhật món ăn");
      model.addAttribute("dishId", id);
      model.addAttribute("currentImageUrl", dishes.findById(id).getImageUrl());
      return "admin/dish-form";
    }
    redirect.addFlashAttribute("notice", "Đã cập nhật món ăn.");
    return "redirect:/admin/dishes";
  }

  @PostMapping("/{id}/delete")
  public String delete(@PathVariable Long id, RedirectAttributes redirect) {
    dishes.delete(id);
    redirect.addFlashAttribute("notice", "Đã xóa món ăn.");
    return "redirect:/admin/dishes";
  }
}
