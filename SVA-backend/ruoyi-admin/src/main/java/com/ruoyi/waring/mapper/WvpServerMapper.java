package com.ruoyi.waring.mapper;

import com.ruoyi.waring.domain.WvpServer;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface WvpServerMapper {

    WvpServer selectEnabledById(Long id);
}
