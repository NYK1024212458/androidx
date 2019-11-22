/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.core;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** The direction the camera faces relative to device screen. */
@IntDef({LensFacing.FRONT, LensFacing.BACK})
@Retention(RetentionPolicy.SOURCE)
public @interface LensFacing {
    /** A camera on the device facing the same direction as the device's screen. */
    int FRONT = 0;
    /** A camera on the device facing the opposite direction as the device's screen. */
    int BACK = 1;
}