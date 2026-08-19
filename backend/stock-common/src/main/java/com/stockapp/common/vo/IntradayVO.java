package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** 分时图数据 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntradayVO {
    private String code;
    private BigDecimal preClose;
    private BigDecimal high;
    private BigDecimal low;
    private List<IntradayPointVO> points;
    private Boolean mock;
}
