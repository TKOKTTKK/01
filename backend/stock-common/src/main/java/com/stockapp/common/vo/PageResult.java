package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页结果。
 * 股票池扩到几千只后，任何"全量返回"的列表接口都会变成响应体巨大、
 * 前端渲染卡顿的隐患——分页把返回条数固定在 size，跟股票总数解耦。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    private List<T> list;
    private long total;
    private int page;
    private int size;

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        return PageResult.<T>builder().list(list).total(total).page(page).size(size).build();
    }
}
