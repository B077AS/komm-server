package com.kommserver.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomRoleCreateRequest {
    private String roleName;
    private String color;
    private List<String> permissions;
}
