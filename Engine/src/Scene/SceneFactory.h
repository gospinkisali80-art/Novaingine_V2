#pragma once

#include "Scene.h"

namespace nova {

class SceneFactory {
public:
    static void buildDefaultScene(Scene& scene);
    static void buildShowcaseScene(Scene& scene);
};

}
