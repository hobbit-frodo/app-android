#couldn't set gradle jdk otherwise
#Android Studio and Gradle are using different locations for the JDK.
#				Android Studio: /usr/local/java/jdk1.8.0_144
#				Gradle: /usr/local/android-studio/jre
cat gradle.properties  | sed s/#org.gradle.java/org.gradle.java/ >gradle.properties.txt
mv gradle.properties.txt gradle.properties
#couldn't fix this error otherwise:
# 
cat app/build.gradle  | sed s/\\\/\\\/compileOptions/compileOptions/ > app/build.gradle.txt
mv app/build.gradle.txt  app/build.gradle

cat app/build.gradle  | sed "s/.*sourceCompatibility/         sourceCompatibility/" >app/build.gradle.txt
mv app/build.gradle.txt  app/build.gradle

cat app/build.gradle  | sed "s/.*    targetCompatibility/        targetCompatibility/" >app/build.gradle.txt
mv app/build.gradle.txt  app/build.gradle

cat app/build.gradle  | sed "s/.*compileOptions}/    }/" >  app/build.gradle.txt
mv app/build.gradle.txt  app/build.gradle


