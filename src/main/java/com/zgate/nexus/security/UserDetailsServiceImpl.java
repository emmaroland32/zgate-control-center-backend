package com.zgate.nexus.security;

import com.zgate.nexus.repository.NexusUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final NexusUserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
            .map(u -> new User(
                u.getEmail(),
                u.getPasswordHash(),
                u.isActive(),
                true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))
            ))
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }
}
