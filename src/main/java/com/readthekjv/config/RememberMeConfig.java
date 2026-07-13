package com.readthekjv.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

import javax.sql.DataSource;

/**
 * Kept separate from SecurityConfig: OAuth2SuccessHandler needs the RememberMeServices bean,
 * and SecurityConfig needs OAuth2SuccessHandler — defining these beans on SecurityConfig itself
 * would create a circular bean dependency.
 */
@Configuration
public class RememberMeConfig {

    private static final int REMEMBER_ME_VALIDITY_SECONDS = 60 * 60 * 24 * 30; // 30 days

    @Bean
    public PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        return repository;
    }

    @Bean
    public PersistentTokenBasedRememberMeServices rememberMeServices(
            @Value("${security.remember-me.key}") String key,
            UserDetailsService userDetailsService,
            PersistentTokenRepository persistentTokenRepository) {
        PersistentTokenBasedRememberMeServices services =
                new PersistentTokenBasedRememberMeServices(key, userDetailsService, persistentTokenRepository);
        services.setTokenValiditySeconds(REMEMBER_ME_VALIDITY_SECONDS);
        services.setAlwaysRemember(true); // no remember-me checkbox in the UI — always issue the cookie
        return services;
    }
}
