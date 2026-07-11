package com.example.agent.sql;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SqlErrorEvidence {

    private String errorType;

    private String rawMessage;

    private List<String> tables = new ArrayList<>();

    private List<String> columns = new ArrayList<>();

    private List<String> keys = new ArrayList<>();

    private List<String> parameters = new ArrayList<>();

    private String duplicateValue;

    private String nearSql;

    private String suggestion;
}