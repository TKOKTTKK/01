package com.stockapp.api;

import com.stockapp.api.controller.StockController;
import com.stockapp.api.exception.GlobalExceptionHandler;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.vo.StockVO;
import com.stockapp.service.KlineService;
import com.stockapp.service.MarketService;
import com.stockapp.service.NewsService;
import com.stockapp.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 股票 API 测试：搜索 / 详情 / 不存在股票 */
@ExtendWith(MockitoExtension.class)
class StockControllerTest {

    @Mock private StockService stockService;
    @Mock private MarketService marketService;
    @Mock private KlineService klineService;
    @Mock private NewsService newsService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new StockController(stockService, marketService, klineService, newsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void search_ok() throws Exception {
        when(stockService.search(anyString())).thenReturn(List.of(
                StockVO.builder().code("600519").name("贵州茅台").build()));
        mvc.perform(get("/api/stocks/search").param("keyword", "茅台"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].code").value("600519"));
    }

    @Test
    void search_blankKeyword_shouldReturnParamError() throws Exception {
        mvc.perform(get("/api/stocks/search").param("keyword", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void detail_notFound_shouldReturn40001() throws Exception {
        when(stockService.getByCode(anyString()))
                .thenThrow(new BizException(ErrorCode.STOCK_NOT_FOUND));
        mvc.perform(get("/api/stocks/999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("股票不存在"));
    }
}
