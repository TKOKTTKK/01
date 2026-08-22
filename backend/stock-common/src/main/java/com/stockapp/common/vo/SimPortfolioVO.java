package com.stockapp.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 账户总览 + 持仓的合并快照。
 * 一次请求内两者基于同一份行情计算，避免「总资产」与「持仓明细之和」
 * 因跨越 Redis 缓存过期边界而出现几分钱的不一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimPortfolioVO {
    private SimAccountVO account;
    private List<SimPositionVO> positions;
}
