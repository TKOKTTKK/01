package com.stockapp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stockapp.common.dto.SimOrderRequest;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.vo.QuoteVO;
import com.stockapp.common.vo.SimAccountVO;
import com.stockapp.common.vo.SimCashFlowVO;
import com.stockapp.common.vo.SimPositionVO;
import com.stockapp.common.vo.SimTradeVO;
import com.stockapp.dao.entity.SimulationAccount;
import com.stockapp.dao.entity.SimulationCashFlow;
import com.stockapp.dao.entity.SimulationOrder;
import com.stockapp.dao.entity.SimulationPosition;
import com.stockapp.dao.entity.SimulationTrade;
import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.mapper.SimulationAccountMapper;
import com.stockapp.dao.mapper.SimulationCashFlowMapper;
import com.stockapp.dao.mapper.SimulationOrderMapper;
import com.stockapp.dao.mapper.SimulationPositionMapper;
import com.stockapp.dao.mapper.SimulationTradeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟交易服务（虚拟资金，不涉及真实交易）。
 * 买卖使用当前行情价格立即成交；买入需 100 股整数倍；
 * 资金、持仓、订单、成交、流水在同一事务内落库，保证一致性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationService {

    private final SimulationAccountMapper accountMapper;
    private final SimulationPositionMapper positionMapper;
    private final SimulationOrderMapper orderMapper;
    private final SimulationTradeMapper tradeMapper;
    private final SimulationCashFlowMapper cashFlowMapper;
    private final StockService stockService;
    private final MarketService marketService;

    @Value("${app.sim.initial-cash:1000000}")
    private BigDecimal initialCash;

    // ---------------- 账户 ----------------

    /** 获取（不存在则开通）当前用户的模拟账户 */
    @Transactional
    public SimulationAccount ensureAccount(Long userId) {
        SimulationAccount acc = accountMapper.selectOne(
                new LambdaQueryWrapper<SimulationAccount>().eq(SimulationAccount::getUserId, userId));
        if (acc != null) {
            return acc;
        }
        acc = new SimulationAccount();
        acc.setUserId(userId);
        acc.setInitialCash(initialCash);
        acc.setAvailableCash(initialCash);
        acc.setFrozenCash(BigDecimal.ZERO);
        try {
            accountMapper.insert(acc);
            SimulationCashFlow flow = new SimulationCashFlow();
            flow.setAccountId(acc.getId());
            flow.setType("INIT");
            flow.setAmount(initialCash);
            flow.setBalance(initialCash);
            flow.setDescription("开通模拟账户，初始资金入账");
            cashFlowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            // 并发开户兜底：读已存在的
            acc = accountMapper.selectOne(
                    new LambdaQueryWrapper<SimulationAccount>().eq(SimulationAccount::getUserId, userId));
        }
        return acc;
    }

    /** 行级锁读取账户（事务内使用），避免并发下单资金错乱 */
    private SimulationAccount lockAccount(Long userId) {
        SimulationAccount acc = accountMapper.selectOne(
                new LambdaQueryWrapper<SimulationAccount>()
                        .eq(SimulationAccount::getUserId, userId)
                        .last("FOR UPDATE"));
        if (acc == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return acc;
    }

    /** 账户总览（含按实时行情计算的市值/盈亏） */
    public SimAccountVO getAccountOverview(Long userId) {
        SimulationAccount acc = ensureAccount(userId);
        List<SimPositionVO> positions = listPositions(userId);
        BigDecimal mv = BigDecimal.ZERO;
        BigDecimal todayProfit = BigDecimal.ZERO;
        for (SimPositionVO p : positions) {
            if (p.getMarketValue() != null) {
                mv = mv.add(p.getMarketValue());
            }
            if (p.getTodayProfit() != null) {
                todayProfit = todayProfit.add(p.getTodayProfit());
            }
        }
        BigDecimal total = acc.getAvailableCash().add(acc.getFrozenCash()).add(mv);
        BigDecimal profit = total.subtract(acc.getInitialCash());
        BigDecimal rate = acc.getInitialCash().signum() == 0 ? BigDecimal.ZERO
                : profit.multiply(BigDecimal.valueOf(100))
                        .divide(acc.getInitialCash(), 2, RoundingMode.HALF_UP);
        return SimAccountVO.builder()
                .totalAssets(total.setScale(2, RoundingMode.HALF_UP))
                .availableCash(acc.getAvailableCash())
                .frozenCash(acc.getFrozenCash())
                .positionMarketValue(mv.setScale(2, RoundingMode.HALF_UP))
                .todayProfit(todayProfit.setScale(2, RoundingMode.HALF_UP))
                .totalProfit(profit.setScale(2, RoundingMode.HALF_UP))
                .totalProfitRate(rate)
                .initialCash(acc.getInitialCash())
                .mock(marketService.isMock())
                .build();
    }

    // ---------------- 持仓 ----------------

    /** 持仓列表：市值/浮动盈亏/收益率/今日盈亏 全部按当前行情动态计算 */
    public List<SimPositionVO> listPositions(Long userId) {
        SimulationAccount acc = ensureAccount(userId);
        List<SimulationPosition> list = positionMapper.selectList(
                new LambdaQueryWrapper<SimulationPosition>()
                        .eq(SimulationPosition::getAccountId, acc.getId())
                        .gt(SimulationPosition::getQuantity, 0L)
                        .orderByDesc(SimulationPosition::getUpdatedAt));
        List<SimPositionVO> result = new ArrayList<>();
        for (SimulationPosition p : list) {
            Stock stock = stockService.getById(p.getStockId());
            BigDecimal qty = BigDecimal.valueOf(p.getQuantity());
            SimPositionVO.SimPositionVOBuilder b = SimPositionVO.builder()
                    .stockId(p.getStockId())
                    .code(stock.getCode())
                    .name(stock.getName())
                    .quantity(p.getQuantity())
                    .availableQuantity(p.getAvailableQuantity())
                    .avgCost(p.getAvgCost());
            try {
                QuoteVO q = marketService.getQuote(stock.getCode());
                BigDecimal price = marketService.getQuote(stock.getCode(), stock.getName()).getPrice();
                BigDecimal mv = price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
                BigDecimal profit = price.subtract(p.getAvgCost()).multiply(qty)
                        .setScale(2, RoundingMode.HALF_UP);
                BigDecimal cost = p.getAvgCost().multiply(qty);
                BigDecimal rate = cost.signum() == 0 ? BigDecimal.ZERO
                        : profit.multiply(BigDecimal.valueOf(100)).divide(cost, 2, RoundingMode.HALF_UP);
                BigDecimal today = price.subtract(q.getPreClose()).multiply(qty)
                        .setScale(2, RoundingMode.HALF_UP);
                b.price(price).marketValue(mv).profit(profit).profitRate(rate).todayProfit(today);
            } catch (Exception e) {
                log.warn("持仓行情获取失败 stockId={}: {}", p.getStockId(), e.getMessage());
            }
            result.add(b.build());
        }
        return result;
    }

    // ---------------- 下单（买入 / 卖出） ----------------

    /** 统一下单入口：按当前行情价立即成交 */
    @Transactional
    public SimTradeVO placeOrder(Long userId, SimOrderRequest req) {
        String side = req.getSide() == null ? "" : req.getSide().toUpperCase();
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "交易方向必须为 BUY 或 SELL");
        }
        long qty = req.getQuantity();
        if (qty <= 0) {
            throw new BizException(ErrorCode.SIM_QUANTITY_INVALID);
        }
        if ("BUY".equals(side) && qty % 100 != 0) {
            throw new BizException(ErrorCode.SIM_QUANTITY_INVALID, "买入数量必须是 100 股的整数倍");
        }
        Stock stock = stockService.getByCode(req.getCode());
        BigDecimal price = marketService.getQuote(stock.getCode(), stock.getName()).getPrice();
        BigDecimal amount = price.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

        ensureAccount(userId);
        SimulationAccount acc = lockAccount(userId);

        if ("BUY".equals(side)) {
            doBuy(acc, stock, qty, price, amount);
        } else {
            doSell(acc, stock, qty, price, amount);
        }

        SimulationOrder order = new SimulationOrder();
        order.setAccountId(acc.getId());
        order.setStockId(stock.getId());
        order.setSide(side);
        order.setQuantity(qty);
        order.setPrice(price);
        order.setAmount(amount);
        order.setStatus("FILLED");
        orderMapper.insert(order);

        SimulationTrade trade = new SimulationTrade();
        trade.setOrderId(order.getId());
        trade.setAccountId(acc.getId());
        trade.setStockId(stock.getId());
        trade.setSide(side);
        trade.setQuantity(qty);
        trade.setPrice(price);
        trade.setAmount(amount);
        tradeMapper.insert(trade);

        SimulationCashFlow flow = new SimulationCashFlow();
        flow.setAccountId(acc.getId());
        flow.setType(side);
        flow.setAmount("BUY".equals(side) ? amount.negate() : amount);
        flow.setBalance(acc.getAvailableCash());
        flow.setDescription(("BUY".equals(side) ? "买入" : "卖出") + stock.getName()
                + " " + qty + "股 @" + price);
        cashFlowMapper.insert(flow);

        return SimTradeVO.builder()
                .id(trade.getId())
                .code(stock.getCode())
                .name(stock.getName())
                .side(side)
                .quantity(qty)
                .price(price)
                .amount(amount)
                .createdAt(trade.getCreatedAt())
                .build();
    }

    private void doBuy(SimulationAccount acc, Stock stock, long qty,
                       BigDecimal price, BigDecimal amount) {
        if (acc.getAvailableCash().compareTo(amount) < 0) {
            throw new BizException(ErrorCode.SIM_CASH_NOT_ENOUGH);
        }
        acc.setAvailableCash(acc.getAvailableCash().subtract(amount));
        accountMapper.updateById(acc);

        SimulationPosition pos = positionMapper.selectOne(
                new LambdaQueryWrapper<SimulationPosition>()
                        .eq(SimulationPosition::getAccountId, acc.getId())
                        .eq(SimulationPosition::getStockId, stock.getId()));
        if (pos == null) {
            pos = new SimulationPosition();
            pos.setAccountId(acc.getId());
            pos.setStockId(stock.getId());
            pos.setQuantity(qty);
            pos.setAvailableQuantity(qty);
            pos.setAvgCost(price);
            positionMapper.insert(pos);
        } else {
            long newQty = pos.getQuantity() + qty;
            BigDecimal oldCost = pos.getAvgCost().multiply(BigDecimal.valueOf(pos.getQuantity()));
            BigDecimal avg = oldCost.add(amount)
                    .divide(BigDecimal.valueOf(newQty), 3, RoundingMode.HALF_UP);
            pos.setQuantity(newQty);
            pos.setAvailableQuantity(pos.getAvailableQuantity() + qty);
            pos.setAvgCost(avg);
            positionMapper.updateById(pos);
        }
    }

    private void doSell(SimulationAccount acc, Stock stock, long qty,
                        BigDecimal price, BigDecimal amount) {
        SimulationPosition pos = positionMapper.selectOne(
                new LambdaQueryWrapper<SimulationPosition>()
                        .eq(SimulationPosition::getAccountId, acc.getId())
                        .eq(SimulationPosition::getStockId, stock.getId()));
        if (pos == null || pos.getAvailableQuantity() < qty) {
            throw new BizException(ErrorCode.SIM_POSITION_NOT_ENOUGH);
        }
        pos.setQuantity(pos.getQuantity() - qty);
        pos.setAvailableQuantity(pos.getAvailableQuantity() - qty);
        if (pos.getQuantity() <= 0) {
            positionMapper.deleteById(pos.getId());
        } else {
            positionMapper.updateById(pos);
        }
        acc.setAvailableCash(acc.getAvailableCash().add(amount));
        accountMapper.updateById(acc);
    }

    // ---------------- 记录查询 ----------------

    public List<SimTradeVO> listTrades(Long userId, int limit) {
        SimulationAccount acc = ensureAccount(userId);
        List<SimulationTrade> trades = tradeMapper.selectList(
                new LambdaQueryWrapper<SimulationTrade>()
                        .eq(SimulationTrade::getAccountId, acc.getId())
                        .orderByDesc(SimulationTrade::getCreatedAt)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
        Map<Long, Stock> cache = new HashMap<>();
        List<SimTradeVO> result = new ArrayList<>();
        for (SimulationTrade t : trades) {
            Stock stock = cache.computeIfAbsent(t.getStockId(), stockService::getById);
            result.add(SimTradeVO.builder()
                    .id(t.getId())
                    .code(stock.getCode())
                    .name(stock.getName())
                    .side(t.getSide())
                    .quantity(t.getQuantity())
                    .price(t.getPrice())
                    .amount(t.getAmount())
                    .createdAt(t.getCreatedAt())
                    .build());
        }
        return result;
    }

    public List<SimCashFlowVO> listCashFlows(Long userId, int limit) {
        SimulationAccount acc = ensureAccount(userId);
        List<SimulationCashFlow> flows = cashFlowMapper.selectList(
                new LambdaQueryWrapper<SimulationCashFlow>()
                        .eq(SimulationCashFlow::getAccountId, acc.getId())
                        .orderByDesc(SimulationCashFlow::getCreatedAt)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
        return flows.stream().map(f -> SimCashFlowVO.builder()
                .id(f.getId())
                .type(f.getType())
                .amount(f.getAmount())
                .balance(f.getBalance())
                .description(f.getDescription())
                .createdAt(f.getCreatedAt())
                .build()).toList();
    }
}
