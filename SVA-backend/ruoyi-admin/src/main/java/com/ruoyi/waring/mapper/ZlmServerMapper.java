package com.ruoyi.waring.mapper;

import com.ruoyi.waring.domain.ZlmServer;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface ZlmServerMapper {

    ZlmServer selectEnabledById(Long id);

    List<ZlmServer> selectEnabledList();

    /** 按 id 查(不限 enabled, 供启动自愈等场景)。 */
    ZlmServer selectZlmServerById(Long id);

    /** 动态更新(只更新非空字段), 启动自愈用来改 host。 */
    int updateZlmServer(ZlmServer server);
}
