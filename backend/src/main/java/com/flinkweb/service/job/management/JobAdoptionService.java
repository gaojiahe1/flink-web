package com.flinkweb.service.job.management;

import com.flinkweb.dto.job.management.AdoptJobRequest;
import com.flinkweb.entity.job.JobDraft;
import java.util.*;

/** 服务契约：将既有 Application 作业登记到本地元数据；纳管仅查询集群，保留原 spec。 */
public interface JobAdoptionService {
  /** 核对用户确认的 UID、Namespace 和作业类型，随后在短事务中登记元数据。 */
  JobDraft adopt(AdoptJobRequest request);
}
