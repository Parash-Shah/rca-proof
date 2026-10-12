package dev.rcaproof.api;

import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/v1/verifications/{id}")
public class ReviewController {
    private final ReviewService reviews;
    private final String reviewKey;
    public ReviewController(ReviewService reviews,@org.springframework.beans.factory.annotation.Value("${rcaproof.github.review-key:}") String reviewKey) { this.reviews=reviews; this.reviewKey=reviewKey; }
    @PostMapping("/review") public ReviewService.Review prepare(@PathVariable UUID id) { return reviews.prepare(id); }
    @GetMapping("/review") public ReviewService.Review get(@PathVariable UUID id) { return reviews.get(id); }
    @PostMapping("/pull-request") public ReviewService.Review publish(@PathVariable UUID id,@RequestHeader(value="X-RCAProof-Review-Key",defaultValue="") String suppliedKey) {
        if (reviewKey.isBlank()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Review publication is not configured");
        if (!java.security.MessageDigest.isEqual(reviewKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),suppliedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Review key required");
        reviews.prepare(id);
        return reviews.publish(id);
    }
}
