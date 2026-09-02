package com.stockapp.api;

import com.stockapp.api.controller.StockController;
import com.stockapp.api.exception.GlobalExceptionHandler;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.vo.DetailBootstrapVO;
import com.stockapp.common.vo.QuoteVO;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    void detailBootstrapBatch_ok() throws Exception {
        when(stockService.detailBootstrapBatch(anyList())).thenReturn(Map.of(
                "600519", DetailBootstrapVO.builder()
                        .stock(StockVO.builder().code("600519").name("贵州茅台").build())
                        .quote(QuoteVO.builder().code("600519").price(new java.math.BigDecimal("1800.00")).build())
                        .build()));
        mvc.perform(post("/api/stocks/detail-bootstrap/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[\"600519\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.600519.stock.code").value("600519"));
    }

    @Test
    void detailBootstrapBatch_emptyCodes_shouldReturnParamError() throws Exception {
        mvc.perform(post("/api/stocks/detail-bootstrap/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void detailBootstrapBatch_tooManyCodes_shouldReturnParamError() throws Exception {
        String codes = "[" + "\"600519\",".repeat(14) + "\"000001\"]"; // 15 个，超过上限 13
        mvc.perform(post("/api/stocks/detail-bootstrap/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":" + codes + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }
}
