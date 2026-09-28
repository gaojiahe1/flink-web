package com.flinkweb.service.catalog.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.repository.metadata.RecordRepository;
import org.springframework.stereotype.Component;

/** 首次启动时补充内置 Application 和 PodTemplate 模板，不覆盖用户已有模板。 */
@Component
public class DefaultTemplates {
  /** 在模板不存在时写入内置模板，保留用户已经修改的内容。 */
  public DefaultTemplates(RecordRepository store, ObjectMapper mapper) {
    if (store.find("system", "template-seed") != null) return;
    String base =
        """
        apiVersion: flink.apache.org/v1beta1
        kind: FlinkDeployment
        metadata:
          name: example-job
        spec:
          image: flink:1.20.0-scala_2.12
          flinkVersion: v1_20
          flinkConfiguration:
            taskmanager.numberOfTaskSlots: "1"
          jobManager:
            resource:
              memory: 1024m
              cpu: 1
          taskManager:
            resource:
              memory: 2048m
              cpu: 1
          job:
            jarURI: local:///opt/flink/usrlib/job.jar
            parallelism: 1
            upgradeMode: savepoint
            state: running
        """;
    if (store.find("templates", "application-basic") == null)
      store.save(
          "templates",
          "application-basic",
          mapper
              .createObjectNode()
              .put("name", "Application 基础模板")
              .put("description", "独立作业；状态目录与 checkpoint 保留策略由平台补齐。")
              .put("yaml", base),
          0);
    String pod =
        base
            + """
                podTemplate:
                  metadata:
                    labels:
                      environment: production
                  spec:
                    containers:
                      - name: flink-main-container
                        env:
                          - name: TZ
                            value: Asia/Shanghai
              """;
    if (store.find("templates", "application-pod") == null)
      store.save(
          "templates",
          "application-pod",
          mapper
              .createObjectNode()
              .put("name", "PodTemplate 标签与环境变量")
              .put("description", "在基础模板上增加 Pod 标签和环境变量。")
              .put("yaml", pod),
          0);
    store.save("system", "template-seed", mapper.createObjectNode().put("version", 1), 0);
  }
}
