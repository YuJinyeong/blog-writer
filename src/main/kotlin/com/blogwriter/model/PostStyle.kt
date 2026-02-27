package com.blogwriter.model

enum class PostStyle(val displayName: String, val promptDescription: String) {
    CASUAL("친근한 일상", "친근하고 가벼운 일상 블로그 톤. '~했어요', '~인데요' 같은 구어체 사용"),
    REVIEW("정보성 리뷰", "정보를 체계적으로 전달하는 리뷰 톤. 위치, 가격, 메뉴 등을 정리하여 작성"),
    TRAVEL("여행 에세이", "감성적인 여행 에세이 톤. 여행의 느낌과 감동을 담아 서술")
}
