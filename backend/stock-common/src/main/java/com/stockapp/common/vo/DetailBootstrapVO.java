package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票详情页首屏聚合数据（冷启动专用）。
 *
 * 把前端首次打开详情页时并行的 getStock + getQuote + getIntraday 三次请求
 * 合并为一次，移动端弱网下省掉两次完整的 HTTP 往返。
 * 三个字段各自的独立接口全部保留（列表、轮询、其他调用场景继续用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetailBootstrapVO {
    private StockVO stock;
    private QuoteVO quote;
    private IntradayVO intraday;
}
