package com.stockapp.api.controller;

import com.stockapp.api.security.CurrentUser;
import com.stockapp.common.dto.SimOrderRequest;
import com.stockapp.common.result.Result;
import com.stockapp.common.vo.SimAccountVO;
import com.stockapp.common.vo.SimCashFlowVO;
import com.stockapp.common.vo.SimPositionVO;
import com.stockapp.common.vo.SimTradeVO;
import com.stockapp.service.SimulationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 模拟交易 API（全部需要登录；虚拟资金，不产生真实交易） */
@RestController
@RequestMapping("/api/sim")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationService simulationService;

    /** 账户总览：总资产 / 可用 / 冻结 / 持仓市值 / 今日盈亏 / 总收益 / 收益率 */
    @GetMapping("/account")
    public Result<SimAccountVO> account() {
        return Result.success(simulationService.getAccountOverview(CurrentUser.id()));
    }

    /** 持仓列表（含实时市值与浮动盈亏） */
    @GetMapping("/positions")
    public Result<List<SimPositionVO>> positions() {
        return Result.success(simulationService.listPositions(CurrentUser.id()));
    }

    /** 下单（BUY / SELL，按当前行情价立即成交） */
    @PostMapping("/order")
    public Result<SimTradeVO> order(@Valid @RequestBody SimOrderRequest req) {
        return Result.success(simulationService.placeOrder(CurrentUser.id(), req));
    }

    /** 成交记录 */
    @GetMapping("/trades")
    public Result<List<SimTradeVO>> trades(@RequestParam(defaultValue = "50") int limit) {
        return Result.success(simulationService.listTrades(CurrentUser.id(), limit));
    }

    /** 资金流水 */
    @GetMapping("/cashflows")
    public Result<List<SimCashFlowVO>> cashflows(@RequestParam(defaultValue = "50") int limit) {
        return Result.success(simulationService.listCashFlows(CurrentUser.id(), limit));
    }
}
