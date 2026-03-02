package com.readthekjv.service;

import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email.toLowerCase().strip())
            .orElseThrow(() -> new UsernameNotFoundException("No account for: " + email));

        return org.springframework.security.core.userdetails.User.builder()
            .username(user.getEmail())
            .password(user.getPasswordHash() != null
                    ? user.getPasswordHash()
                    : "{noop}GOOGLE_OAUTH_NO_PASSWORD")
            .roles("USER")
            .build();
    }
}
