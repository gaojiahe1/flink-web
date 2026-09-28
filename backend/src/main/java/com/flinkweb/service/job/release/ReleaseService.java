package com.flinkweb.service.job.release;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.service.job.creation.JobService;
import java.util.*;

/** 服务契约：保存不可变的发布内容与校验摘要；回滚只恢复草稿，部署和状态恢复由用户单独执行。 */
public interface ReleaseService {
  /** 固定本次提交内容，不保存生成 Secret 的明文或密文数据。 */
  ObjectNode capture(JobDraft job, ObjectNode rendered);

  /** 返回指定作业的不可变版本，新版本优先。 */
  List<ObjectNode> list(String id);

  /** 验证发布版本确实属于当前作业，防止跨作业恢复。 */
  ObjectNode require(String jobId, String releaseId);

  /** 恢复发布时的声明式 YAML；密钥引用重新解析，不宣称密钥内容已回滚。 */
  JobDraft restore(String id, String releaseId, long revision, JobService jobs);
}
