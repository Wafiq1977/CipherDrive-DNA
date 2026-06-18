package com.cipherdrive.dna.service.impl;

import com.cipherdrive.dna.entity.User;
import com.cipherdrive.dna.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

/**
 * Custom UserDetailsService for Spring Security integration.
 *
 * Loads user with role eagerly (JOIN FETCH) to avoid:
 *   1. LazyInitializationException outside transaction
 *   2. N+1 queries during SecurityContext population
 *
 * Returns Spring Security UserDetails with:
 *   - username as principal
 *   - passwordHash as credentials
 *   - roleName as GrantedAuthority
 *   - isEnabled + !isLocked as account flags
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsernameWithRole(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        String.format("User not found: %s", username)));

        var authority = new SimpleGrantedAuthority(user.getRole().getRoleName());

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPasswordHash(),
                user.getIsActive(),
                true,  // accountNonExpired
                true,  // credentialsNonExpired
                !user.getIsLocked(),  // accountNonLocked
                Collections.singletonList(authority)
        );
    }

    /**
     * Get the full User entity by username — used by AuthenticationService.
     * Unlike loadUserByUsername, returns the domain entity, not Spring Security UserDetails.
     */
    @Transactional(readOnly = true)
    public User getUserEntityByUsername(String username) {
        return userRepository.findByUsernameWithRole(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        String.format("User not found: %s", username)));
    }
}
