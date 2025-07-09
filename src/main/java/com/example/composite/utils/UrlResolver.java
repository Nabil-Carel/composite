package com.example.composite.utils;

import com.example.composite.model.ResponseTracker;
import com.example.composite.model.request.SubRequest;
import lombok.experimental.UtilityClass;
import org.springframework.beans.BeanWrapperImpl;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for resolving placeholders in URLs of SubRequests.
 * Placeholders are expected to be in the format ${objectId.propertyPath}, where objectId
 * corresponds to a key in the ResponseTracker's subResponseMap and propertyPath is a path
 * to a property on the object.
 */
@UtilityClass
public class UrlResolver {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * Resolves placeholders in the URL of a SubRequest using values from the ResponseTracker.
     * Placeholders are expected to be in the format ${objectId.propertyPath}, where objectId
     * corresponds to a key in the ResponseTracker's subResponseMap and propertyPath is a path
     * to a property on the object.
     *
     * @param subRequest The SubRequest containing the URL with placeholders.
     * @param tracker The ResponseTracker containing resolved responses.
     * @return The resolved URL with placeholders replaced by actual values.
     */
    public static String resolve(SubRequest subRequest, ResponseTracker tracker) {
        String url = subRequest.getUrl();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(url);
        StringBuilder resolvedUrl = new StringBuilder();

        while (matcher.find()) {
            String expression = matcher.group(1); // e.g. "user.address.city"
            String[] parts = expression.split("\\.", 2); // Split into objectId and path

            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid placeholder: " + matcher.group());
            }

            String objectId = parts[0];
            String propertyPath = parts[1];

            Object root = tracker.getSubResponseMap().get(objectId).getBody();
            String replacement = getResolvedValue(root, objectId, propertyPath);

            matcher.appendReplacement(resolvedUrl, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(resolvedUrl);
        subRequest.setResolvedUrl(resolvedUrl.toString());
        return resolvedUrl.toString();
    }

    /**
     * Resolves the value of a property path on an object identified by objectId.
     *
     * @param root The root object from which to resolve the property.
     * @param objectId The identifier for the object.
     * @param propertyPath The path to the property to resolve.
     * @return The resolved value as a String.
     */
    private static String getResolvedValue(Object root, String objectId, String propertyPath) {
        if (root == null) {
            throw new IllegalArgumentException("No object found for id: " + objectId);
        }

        BeanWrapperImpl wrapper = new BeanWrapperImpl(root);
        Object value;
        try {
            value = wrapper.getPropertyValue(propertyPath);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to resolve property path '" + propertyPath + "' on object '" + objectId + "'", e);
        }

        return value != null ? value.toString() : "";
    }
}