package com.example.composite.model.request;

import lombok.*;
import lombok.experimental.Delegate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Setter
@Builder
@RequiredArgsConstructor
@AllArgsConstructor
public class SubRequest {
    @Delegate(types = SubRequestDto.class)
    private final SubRequestDto subRequestDto;
    private String resolvedUrl;
    private Map<String, String> resolvedHeaders;
    private Set<String> dependencies = new HashSet<>();

    public Set<String> getDependencies() {
        if(dependencies.isEmpty()) {
            Matcher matcher = Pattern.compile("\\$\\{([^}]+)\\}")
                    .matcher(subRequestDto.getUrl());

            while (matcher.find()) {
                String reference = matcher.group(1);
                String refId = reference.split("\\.")[0];
                dependencies.add(refId);
            }
        }

        return dependencies;
    }

}
    