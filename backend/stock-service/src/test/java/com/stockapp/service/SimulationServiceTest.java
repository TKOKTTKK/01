package com.stockapp.service;

import com.stockapp.common.dto.SimOrderRequest;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.dao.mapper.SimulationAccountMapper;
import com.stockapp.dao.mapper.SimulationCashFlowMapper;
import com.stockapp.dao.mapper.SimulationOrderMapper;
import com.stockapp.dao.mapper.SimulationPositionMapper;
import com.stockapp.dao.mapper.SimulationTradeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 模拟交易参数校验测试（数量规则在查行情/查库之前校验，可脱离 DB 测试） */
@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock private SimulationAccountMapper accountMapper;
    @Mock private SimulationPositionMapper positionMapper;
    @Mock private SimulationOrderMapper orderMapper;
    @Mock private SimulationTradeMapper tradeMapper;
    @Mock private SimulationCashFlowMapper cashFlowMapper;
    @Mock private StockService stockService;
    @Mock private MarketService marketService;

    @InjectMocks
    private SimulationService simulationService;

    private SimOrderRequest req(String side, long qty) {
        SimOrderRequest r = new SimOrderRequest();
        r.setCode("600519");
        r.setSide(side);
        r.setQuantity(qty);
        return r;
    }

    @Test
    void buy_notMultipleOf100_shouldReject() {
        BizException e = assertThrows(BizException.class,
                () -> simulationService.placeOrder(1L, req("BUY", 150)));
        assertEquals(ErrorCode.SIM_QUANTITY_INVALID.getCode(), e.getCode());
    }

    @Test
    void order_invalidSide_shouldReject() {
        BizException e = assertThrows(BizException.class,
                () -> simulationService.placeOrder(1L, req("HOLD", 100)));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
    }

    @Test
    void order_zeroQuantity_shouldReject() {
        BizException e = assertThrows(BizException.class,
                () -> simulationService.placeOrder(1L, req("SELL", 0)));
        assertEquals(ErrorCode.SIM_QUANTITY_INVALID.getCode(), e.getCode());
    }
}
