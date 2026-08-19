package com.stockapp.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockapp.dao.entity.StockQuote;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockQuoteMapper extends BaseMapper<StockQuote> {
}
