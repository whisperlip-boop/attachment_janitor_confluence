# `.mvn/jvm.config`

`-Duser.name=BSKIM` 한 줄이다.

maven-archiver 가 jar 매니페스트에 `Built-By: <OS 계정명>` 을 넣는데, 그 값은
JVM 의 `user.name` 프로퍼티다. 그냥 두면 **빌드한 사람의 OS 계정명이 배포되는
jar 에 실린다**(실측으로 발견).

pom 의 `<instructions><Built-By>` 로 덮거나 bnd 의 `-removeheaders` 로 지우는 것은
통하지 않는다 — 이 헤더는 bnd 가 아니라 maven-archiver 가, bnd 가 끝난 뒤에 넣는다.
그래서 JVM 프로퍼티 단계에서 막는다. Maven 3.3+ 가 이 파일을 자동으로 읽으므로
빌드 명령을 바꿀 필요가 없다.
