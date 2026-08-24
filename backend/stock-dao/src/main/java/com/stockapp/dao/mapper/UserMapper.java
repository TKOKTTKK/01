package com.stockapp.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockapp.dao.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
