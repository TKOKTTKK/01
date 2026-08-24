package com.stockapp.api.controller;

import com.stockapp.common.result.Result;
import com.stockapp.common.vo.NewsVO;
import com.stockapp.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final NewsService newsService;

    /** 新闻详情 */
    @GetMapping("/{id}")
    public Result<NewsVO> detail(@PathVariable Long id) {
        return Result.success(newsService.getById(id));
    }
}
