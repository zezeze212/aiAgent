# Unknown column 排查指南

## 典型报错

`Unknown column 'xxx' in 'field list'`

这个错误表示 SQL 引用了某个字段，但数据库实际执行时找不到该字段。

## 常见原因

1. SQL、Mapper XML 或注解 SQL 中写了不存在的字段。
2. 查询字段使用了错误的表别名，例如 `SELECT u.name FROM user t`。
3. Java 实体字段、`@TableField` 映射和数据库字段名不一致。
4. 数据库没有执行最新 DDL，当前环境表结构落后。
5. 连接到了错误数据库或错误 schema。
6. 代码分支、数据库版本和运行环境不一致。

## 排查顺序

1. 先以工具查询到的真实表结构为准。
2. 从报错信息中提取缺失字段名。
3. 确认报错 SQL 实际访问的是哪张表、使用了哪个别名。
4. 对比缺失字段是否存在于工具返回的 `existingColumns` 或 `tableSchema`。
5. 如果字段不存在，检查 Mapper XML、实体类、数据库迁移脚本和运行环境。
6. 如果字段存在，重点检查 SQL 别名、拼接逻辑、数据库连接环境和大小写差异。

## 回答要求

1. 必须优先引用工具返回的 `diagnosis`、`missingColumns`、`existingColumns`、`suggestedActions`。
2. 如果工具已经确认字段不存在，应明确说明字段不存在，不要说成“可能不存在”。
3. 不要枚举完整表结构，除非用户明确要求。
4. 不要直接生成 `ALTER TABLE`，除非用户明确要求新增字段，并提供字段类型、长度、默认值和业务含义。
5. 建议优先从 Mapper XML、实体类字段、数据库版本和连接环境排查。
