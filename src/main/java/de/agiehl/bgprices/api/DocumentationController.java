package de.agiehl.bgprices.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DocumentationController {
    @GetMapping({"/", "/docs", "/docs/", "/swagger-ui.html", "/swagger-ui/index.html"})
    public String documentation() {
        return "redirect:/docs/index.html";
    }
}
