package com.cocos.challenge.instruments.web;

import com.cocos.challenge.instruments.search.InstrumentSearchService;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/instruments")
public class InstrumentController {

  private final InstrumentSearchService searchService;

  public InstrumentController(InstrumentSearchService searchService) {
    this.searchService = searchService;
  }

  @GetMapping
  public InstrumentSearchResponse search(@RequestParam(required = false) @Nullable String query) {
    return new InstrumentSearchResponse(
        searchService.search(query).stream().map(InstrumentSummary::from).toList());
  }
}
