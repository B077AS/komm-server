package com.kommserver.model.converter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<List<String>>() {}.getType();

    @Override
    public String convertToDatabaseColumn(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        return GSON.toJson(list);
    }

    @Override
    public List<String> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        return GSON.fromJson(json, TYPE);
    }
}
