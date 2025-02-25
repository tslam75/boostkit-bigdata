/**
 * Copyright (C) 2022-2022. Huawei Technologies Co., Ltd. All rights reserved.
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef THESTRAL_PLUGIN_MASTER_JNI_COMMON_CPP
#define THESTRAL_PLUGIN_MASTER_JNI_COMMON_CPP

#include "jni_common.h"

spark::CompressionKind GetCompressionType(JNIEnv* env, jstring codec_jstr) {
    auto codec_c = env->GetStringUTFChars(codec_jstr, JNI_FALSE);
    auto codec = std::string(codec_c);
    auto compression_type = GetCompressionType(codec);
    env->ReleaseStringUTFChars(codec_jstr, codec_c);
    return compression_type;
}

jclass CreateGlobalClassReference(JNIEnv* env, const char* class_name) {
    jclass local_class = env->FindClass(class_name);
    jclass global_class = (jclass)env->NewGlobalRef(local_class);
    env->DeleteLocalRef(local_class);
    return global_class;
}

jmethodID GetMethodID(JNIEnv* env, jclass this_class, const char* name, const char* sig) {
    jmethodID ret = env->GetMethodID(this_class, name, sig);
    return ret;
}

#endif //THESTRAL_PLUGIN_MASTER_JNI_COMMON_CPP
