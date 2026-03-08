package com.readthekjv.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class WebController {

    /**
     * Redirect root to the landing page.
     * Preserve legacy deep-links: /?vid=123 → /read?vid=123
     */
    @GetMapping("/")
    public String root(@RequestParam(required = false) String vid) {
        if (vid != null) {
            return "redirect:/read?vid=" + vid;
        }
        return "redirect:/landing.html";
    }

    /** Serve the reader at /read (forwards to the static index.html). */
    @GetMapping("/read")
    public String reader() {
        return "forward:/index.html";
    }

    /** Serve the training page at /train (forwards to the static train.html). */
    @GetMapping("/train")
    public String train() {
        return "forward:/train.html";
    }
}
