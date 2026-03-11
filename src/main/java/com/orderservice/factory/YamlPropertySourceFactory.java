package com.orderservice.factory;


import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;

import java.util.Properties;

/**
 * Custom factory that allows @PropertySource to load .yml files.
 * Spring's built-in @PropertySource only reads .properties files —
 * this bridges that gap using YamlPropertiesFactoryBean.
 */
public class YamlPropertySourceFactory implements PropertySourceFactory {

    @Override
    public PropertySource<?> createPropertySource(String name, EncodedResource encodedResource) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(encodedResource.getResource());

        Properties properties = factory.getObject();

        String sourceName = (name != null && !name.isEmpty())
                ? name
                : encodedResource.getResource().getFilename();

        return new PropertiesPropertySource(sourceName, properties);
    }
}