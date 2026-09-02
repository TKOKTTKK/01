package com.stockapp.common.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量详情页首屏聚合请求：前端视口预取把同一屏内新进入视口的多只股票
 * 打包成一次请求（见前端 viewportPrefetch.ts）。
 *
 * max = 13 跟前端视口预取的 LRU 追踪队列容量（MAX_TRACKED）保持一致——
 * 队列本身就不会攒出超过 13 个待发起的 code，这里的上限是给这个公开
 * 接口的一层防御，防止绕过前端直接传超大 codes 数组打满一次批量查询。
 */
@Data
public class DetailBootstrapBatchRequest {

    @NotEmpty(message = "codes 不能为空")
    @Size(max = 13, message = "一次最多批量 13 只股票")
    private List<String> codes;
}
