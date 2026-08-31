package uz.faceguard.app.core.pipeline

import uz.faceguard.app.R

object EnrollmentSteps {
    private val STEPS = listOf(
        R.string.enroll_step_front,
        R.string.enroll_step_left,
        R.string.enroll_step_right,
        R.string.enroll_step_up,
    )

    fun stepRes(step: Int): Int = STEPS[step]
    fun size(): Int = STEPS.size
}
