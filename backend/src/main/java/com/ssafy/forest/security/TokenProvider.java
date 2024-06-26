package com.ssafy.forest.security;

import com.ssafy.forest.domain.UserDetailsImpl;
import com.ssafy.forest.domain.dto.TokenDto;
import com.ssafy.forest.domain.dto.response.ResponseDto;
import com.ssafy.forest.domain.entity.BlacklistToken;
import com.ssafy.forest.domain.entity.Member;
import com.ssafy.forest.domain.entity.RefreshToken;
import com.ssafy.forest.exception.CustomException;
import com.ssafy.forest.exception.ErrorCode;
import com.ssafy.forest.repository.BlacklistTokenRepository;
import com.ssafy.forest.repository.RefreshTokenRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Key;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class TokenProvider {

    private static final String AUTHORITIES_KEY = "auth";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final long ACCESS_TOKEN_EXPIRE_TIME = 1000 * 60 * 60 * 24;  // 24 시간
    private static final long REFRESH_TOKEN_EXPRIRE_TIME = 1000 * 60 * 60 * 24 * 7;  // 7일

    private final Key key;

    private final RefreshTokenRepository refreshTokenRepository;
    private final BlacklistTokenRepository blacklistTokenRepository;

    // 암호화
    public TokenProvider(@Value("${jwt.secret}") String secretKey,
        RefreshTokenRepository refreshTokenRepository, BlacklistTokenRepository blacklistTokenRepository) {
        this.blacklistTokenRepository = blacklistTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    // 토큰 생성
    public TokenDto generateTokenDto(Member member) {
        long now = (new Date().getTime());

        // AccessToken 생성
        Date accessTokenExpiresIn = new Date(now + ACCESS_TOKEN_EXPIRE_TIME);
        String accessToken = Jwts.builder()
            .setSubject(member.getId().toString())  // memberId
            .claim(AUTHORITIES_KEY, member.getMemberType().toString())
            .setExpiration(accessTokenExpiresIn)
            .signWith(key, SignatureAlgorithm.HS256)
            .compact();

        // RefreshToken 생성
        // (AccessToken의 재발급 목적으로 만들어진 토큰이므로 만료기한 외 별다른 정보를 담지 않는다)
        String refreshToken = Jwts.builder()
            .setExpiration(new Date(now + REFRESH_TOKEN_EXPRIRE_TIME))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact();

        RefreshToken refreshTokenObject = RefreshToken.builder()
            .id(member.getId().toString())
            .member(member)
            .keyValue(refreshToken)
            .build();

        refreshTokenRepository.save(refreshTokenObject);

        return TokenDto.builder()
            .grantType(BEARER_PREFIX)
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .build();
    }

    public Member getMemberFromAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
            || AnonymousAuthenticationToken.class.isAssignableFrom(authentication.getClass())) {
            throw new CustomException(ErrorCode.NOT_FOUND_AUTHENTICATION);
        }
        return ((UserDetailsImpl) authentication.getPrincipal()).getMember();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (SecurityException | MalformedJwtException e) {
            log.info("Invalid JWT signature, 유효하지 않는 JWT 서명 입니다.");
        } catch (ExpiredJwtException e) {
            log.info("Expired JWT token, 만료된 JWT token 입니다.");
        } catch (UnsupportedJwtException e) {
            log.info("Unsupported JWT token, 지원되지 않는 JWT 토큰 입니다.");
        } catch (IllegalArgumentException e) {
            log.info("JWT claims is empty, 잘못된 JWT 토큰 입니다.");
        }
        return false;
    }

    @Transactional(readOnly = true)
    public RefreshToken isPresentRefreshToken(Member member) {
        return refreshTokenRepository.findByMember(member)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_EXIST_REFRESH_TOKEN));
    }

    @Transactional
    public void deleteRefreshToken(Member member) {
        RefreshToken refreshToken = isPresentRefreshToken(member);
        refreshTokenRepository.delete(refreshToken);
    }

    @Transactional(readOnly = true)
    public boolean checkBlacklistToken(String accessToken) {
        return blacklistTokenRepository.existsById(accessToken);
    }

//    public Authentication getAuthentication(HttpServletRequest request) {
//        String token = getAccessToken(request);
//        if (token == null) {
//            return null;
//        } else {
//            Claims claims = Jwts
//                .parserBuilder()
//                .setSigningKey(key)
//                .build()
//                .parseClaimsJws(token)
//                .getBody();
//
//            Collection<? extends GrantedAuthority> authorities =
//                Arrays.stream(claims.get(AUTHORITIES_KEY).toString().split(","))
//                    .map(SimpleGrantedAuthority::new)
//                    .collect(Collectors.toList());
//
//            User principal = new User(claims.getSubject(), "@", authorities);
//
//            return new UsernamePasswordAuthenticationToken(principal, token, authorities);
//        }
//    }

//    public Long getMemberIdByToken(String accessToken) {
//        String token;
//        if (StringUtils.hasText(accessToken) && accessToken.startsWith("Bearer ")) {
//            token = accessToken.substring(7);
//        } else {
//            return null;
//        }
//        Claims claims;
//        try {
//            claims = Jwts
//                .parserBuilder()
//                .setSigningKey(key)
//                .build()
//                .parseClaimsJws(token)
//                .getBody();
//        } catch (ExpiredJwtException e) {
//            return null;
//        }
//
//        return Long.parseLong(claims.getSubject());
//    }

    private String getAccessToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");

        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }

        return null;
    }

//    public String getMemberFromExpiredAccessToken(HttpServletRequest request) throws ParseException {
//        String jwt = getAccessToken(request);
//
//        Base64.Decoder decoder = Base64.getUrlDecoder();
//        assert jwt != null;
//        String[] parts = jwt.split("\\.");
//        JSONParser parser = new JSONParser();
//        JSONObject jsonObject = (JSONObject) parser.parse(new String(decoder.decode(parts[1])));
//        return jsonObject.get("sub").toString();
//    }

    @Transactional
    public Member validateMember(HttpServletRequest request) {
        String refreshTokenOfHeader = request.getHeader("RefreshToken");
        if (!validateToken(refreshTokenOfHeader)) {
            throw new CustomException(ErrorCode.INVALIDATE_REFRESH_TOKEN);
        }

        if (checkBlacklistToken(getAccessToken(request))) {
            throw new CustomException(ErrorCode.BLACKLIST_ACCESS_TOKEN);
        }

        Member member = getMemberFromAuthentication();
        RefreshToken refreshToken = isPresentRefreshToken(member);

        if (!refreshToken.getKeyValue().equals(refreshTokenOfHeader)) {
            throw new CustomException(ErrorCode.MISMATCH_REFRESH_TOKEN);
        }

        return member;
    }

    @Transactional
    public ResponseDto<Member> validateCheck(HttpServletRequest request) {
        // RefreshToken 및 Authorization 유효성 검사
        if (null == request.getHeader("RefreshToken") || null == request.getHeader("Authorization")) {
            throw new CustomException(ErrorCode.BLANK_TOKEN_HEADER);
        }

        // token 정보 유효성 검사
        Member member = validateMember(request);
        if (null == member) {
            throw new CustomException(ErrorCode.EXPIRED_TOKEN);
        }
        return ResponseDto.success(member);
    }

    @Transactional
    public Member getMemberFromAccessToken(HttpServletRequest request) {
        // RefreshToken 및 Authorization 유효성 검사
        if (null == request.getHeader("RefreshToken") || null == request.getHeader("Authorization")) {
            throw new CustomException(ErrorCode.BLANK_TOKEN_HEADER);
        }

        // token 유효성 검사
        return validateMember(request);
    }

    // access token 만료시각 조회
    private LocalDateTime getExpiredDateTime(String token) {
        Claims claims = Jwts.parserBuilder()
            .setSigningKey(key)
            .build()
            .parseClaimsJws(token)
            .getBody();

        return claims.getExpiration().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    public void saveBlacklistToken(HttpServletRequest request) {
        String accessToken = getAccessToken(request);
        log.info("Blacklist add : {}", accessToken);
        blacklistTokenRepository.save(BlacklistToken.builder()
            .keyValue(accessToken)
            .expiredDateTime(getExpiredDateTime(accessToken))
            .build());
    }

}