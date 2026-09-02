package com.stageaccord.identityaccess;

import java.util.UUID;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.stageaccord.identityaccess.application.IdentityStore;

@Service
final class IdentityUserDetailsService implements UserDetailsService {
    private final IdentityStore identities;

    IdentityUserDetailsService(IdentityStore identities) { this.identities = identities; }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        try {
            var account = identities.findAuthenticationByAccountId(UUID.fromString(username))
                    .filter(item -> "active".equals(item.status()))
                    .orElseThrow(() -> new UsernameNotFoundException("account not found"));
            return User.withUsername(account.accountId().toString())
                    .password(account.encodedPassword() == null ? "{noop}unused" : account.encodedPassword())
                    .roles("USER").build();
        } catch (IllegalArgumentException failure) {
            throw new UsernameNotFoundException("account not found", failure);
        }
    }
}
