package com.stockapp.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockapp.dao.entity.StockKline;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockKlineMapper extends BaseMapper<StockKline> {
}
