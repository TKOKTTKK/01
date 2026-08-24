package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 新闻 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsVO {
    private Long id;
    private String stockCode;
    private String title;
    private String source;
    private String url;
    private String content;
    private LocalDateTime publishTime;
}
