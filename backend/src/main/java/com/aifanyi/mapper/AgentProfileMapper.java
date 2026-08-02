package com.aifanyi.mapper;

import com.aifanyi.agent.profile.AgentProfile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** ⑥ 领域档案。放本包是因为 @MapperScan 只扫 com.aifanyi.mapper。 */
public interface AgentProfileMapper extends BaseMapper<AgentProfile> {
}
